---
source: fuzzy-matching.md
sourceHash: 5559746d87dc5310173be0af9c2f5ddbf0f7cea39733424e018e83412a4b23fd
---

# So khớp gần đúng

RuleStone có các hàm chấm điểm mức độ giống nhau của hai cái tên. Hãy dùng chúng trong các luật sàng lọc khách hàng, đối tác hoặc người thụ hưởng theo một danh sách tên, ví dụ một danh sách theo dõi nội bộ trong quy trình AML/KYC.

```
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
```

> **Quan trọng.** Các hàm này là những khối xây dựng, không phải một hệ thống sàng lọc danh sách cấm vận. Chúng chưa được chứng nhận, không xử lý việc chuyển tự giữa các hệ chữ viết, và không biết gì về bí danh, ngày sinh hay quốc tịch. Một kết quả khớp, hoặc việc không có kết quả khớp, đều phải được xử lý và rà soát theo chính sách phòng, chống rửa tiền (AML) của tổ chức bạn. Xem [Hạn chế](#hạn-chế).

- [Các hàm](#các-hàm)
- [Chuẩn hoá](#chuẩn-hoá)
- [Cách tính điểm](#cách-tính-điểm)
- [Viết luật sàng lọc](#viết-luật-sàng-lọc)
- [Chọn ngưỡng](#chọn-ngưỡng)
- [Ví dụ tính toán](#ví-dụ-tính-toán)
- [Hiệu năng và giới hạn](#hiệu-năng-và-giới-hạn)
- [Lỗi](#lỗi)
- [Hạn chế](#hạn-chế)

## Các hàm

| Hàm | Kết quả | Ý nghĩa |
|---|---|---|
| `normalizeName(s)` | `string` | `s` đã bỏ dấu, bỏ phân biệt hoa thường và bỏ dấu câu (xem [Chuẩn hoá](#chuẩn-hoá)). |
| `similarity(a, b)` | `decimal` | Độ tương đồng Jaro-Winkler của hai tên đã chuẩn hoá, từ 0 (không có gì chung) đến 1 (giống hệt). |
| `nameSimilarity(a, b)` | `decimal` | Giá trị lớn hơn giữa `similarity(a, b)` và độ tương đồng của hai tên sau khi sắp xếp các từ, nhờ đó `Van An Nguyen` khớp với `Nguyen Van An`. |
| `levenshtein(a, b)` | `int` | Khoảng cách chỉnh sửa giữa hai tên đã chuẩn hoá: số lần chèn, xoá và thay thế từng ký tự cần thiết để biến tên này thành tên kia. |
| `maxNameSimilarity(name, names)` | `decimal` | Giá trị `nameSimilarity(name, n)` cao nhất trên danh sách `names`; bằng 0 với danh sách rỗng. |

Mọi điểm số:

- là `decimal`, không bao giờ là số dấu phẩy động, nên hãy so sánh với `decimal('0.92')`, không phải `0.92`;
- nằm trong khoảng từ 0 đến 1 và có đúng bốn chữ số thập phân, làm tròn half-even (`0.9611`, `1.0000`);
- có tính đối xứng: `similarity(a, b) == similarity(b, a)`;
- giống hệt nhau trên mọi máy và mọi phiên bản Java, vì được tính bằng số học số nguyên chính xác.

Mọi hàm đều so sánh theo code point Unicode, nên một chữ cái nằm ngoài Basic Multilingual Plane được tính là một ký tự.

## Chuẩn hoá

Mọi hàm đều chuẩn hoá các tham số trước, theo thứ tự sau:

1. Phân rã văn bản (Unicode NFD), nên `ễ` trở thành `e` theo sau là hai dấu kết hợp.
2. Bỏ mọi dấu kết hợp (nhóm Unicode `M`): dấu thanh, dấu mũ, dấu móc, cedilla, umlaut.
3. Thay `đ` và `Đ` bằng `d`. Hai chữ cái tiếng Việt này không có dạng phân rã trong Unicode, nên bước 2 không xử lý được.
4. Chuyển thành chữ thường, không phụ thuộc vào locale của máy chủ.
5. Thay mọi ký tự không phải chữ cái hay chữ số bằng một khoảng trắng: dấu câu, gạch nối, dấu nháy, tab, xuống dòng.
6. Gộp các chuỗi khoảng trắng liên tiếp thành một và cắt khoảng trắng ở hai đầu.

| Đầu vào | `normalizeName` |
|---|---|
| `  NGUYỄN  Văn Đức, Jr. ` | `nguyen van duc jr` |
| `Đặng Thị Ngọc Hân` | `dang thi ngoc han` |
| `José Müller-Lüdenscheidt` | `jose muller ludenscheidt` |

Văn bản ở mọi hệ chữ viết đều giữ nguyên chữ cái: `Владимир Путин` trở thành `владимир путин`, và **không** khớp với `vladimir putin` (xem [Hạn chế](#hạn-chế)). Các chữ cái không được phân rã trong Unicode, như `ø`, `ł`, `æ` và `ß`, được giữ nguyên; chỉ `đ` được ánh xạ.

Nhờ chuẩn hoá, chẳng hạn một cái tên gõ không dấu trên ứng dụng di động được coi là giống hệt cùng cái tên có dấu trên giấy tờ tuỳ thân của khách hàng.

## Cách tính điểm

### `similarity`: Jaro-Winkler

Độ tương đồng Jaro của hai chuỗi đếm số ký tự chung của chúng trong một cửa sổ `max(len(a), len(b)) / 2 - 1` vị trí (*m*) và số ký tự trong số đó bị lệch thứ tự (*t*, bằng một nửa số ký tự lệch thứ tự, làm tròn xuống):

```
jaro = (m / len(a) + m / len(b) + (m - t) / m) / 3        (0 when m = 0)
```

Biến thể của Winkler ưu ái các tên có phần đầu giống nhau. Nếu `jaro` **lớn hơn 0.7**, công thức cộng thêm điểm thưởng cho phần tiền tố chung có độ dài `p` (tối đa 4 ký tự):

```
similarity = jaro + p × 0.1 × (1 − jaro)
```

Đây là định nghĩa trong chương trình `strcmp95` gốc của Winkler, cũng được Apache Commons Text và thư viện Python `jellyfish` sử dụng, nên bạn có thể đối chiếu điểm số với chúng. Ngưỡng 0.7 nghĩa là hai chuỗi chỉ giống nhau ở vài chữ cái đầu sẽ không được thưởng: độ tương đồng Jaro của `abcxyz` và `abcuvw` là 2/3, nên `similarity('abcxyz', 'abcuvw')` vẫn là `0.6667` dù có tiền tố chung `abc`.

Kết quả được tính dưới dạng phân số chính xác và chỉ làm tròn một lần tới bốn chữ số thập phân.

**Tên rỗng.** Hai tên rỗng sau khi chuẩn hoá (ví dụ `''` và `'-'`) được 1 điểm, theo quy ước thông thường. Một tên rỗng và một tên không rỗng được 0 điểm. Trong luật sàng lọc, hãy kiểm tra tường minh trường hợp tên trống; xem [Viết luật sàng lọc](#viết-luật-sàng-lọc).

### `nameSimilarity`: thứ tự từ

Jaro-Winkler so sánh chuỗi theo từng ký tự, nên cùng một cái tên viết theo thứ tự khác sẽ có điểm thấp: `similarity('Nguyen Van An', 'Van An Nguyen')` chỉ là `0.5971`. Tên thường được viết theo những thứ tự khác nhau: họ đứng trước hoặc đứng sau, tên đệm bị chuyển chỗ.

`nameSimilarity` còn so sánh hai tên sau khi sắp xếp các từ (cả hai tên trên đều trở thành `an nguyen van`) và trả về điểm cao hơn trong hai điểm. Các từ được sắp xếp theo mã ký tự. Kết quả không bao giờ thấp hơn `similarity`.

### `levenshtein`: khoảng cách chỉnh sửa

`levenshtein` trả về một số đếm, không phải điểm số. Hãy dùng hàm này khi luật cần diễn đạt "sai tối đa một ký tự", vốn tự nhiên hơn khi viết dưới dạng khoảng cách: `levenshtein(a, b) <= 1`. Hàm này không xử lý thứ tự từ và coi một lần đảo chỗ (`ht` ↔ `th`) là hai lần chỉnh sửa.

## Viết luật sàng lọc

Một bảng hoàn chỉnh chuyển khách hàng sang rà soát khi tên của họ gần giống một mục trong danh sách theo dõi:

```json
{
  "id": "name_screening",
  "version": "1",
  "description": "Flags customers whose name is close to an entry on the internal watchlist",
  "hitPolicy": "FIRST",
  "inputs": {
    "customer": { "fullName": "string" },
    "watchlist": ["string"]
  },
  "outputs": { "decision": "string", "matchScore": "decimal" },
  "rules": [
    {
      "id": "NAME_MISSING",
      "when": "!has(customer.fullName) || normalizeName(customer.fullName) == ''",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "NAME_MISSING"
    },
    {
      "id": "WATCHLIST_HIT",
      "when": "maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')",
      "then": { "decision": "'REVIEW'", "matchScore": "maxNameSimilarity(customer.fullName, watchlist)" },
      "reasonCode": "WATCHLIST_NAME_MATCH"
    },
    { "id": "NO_ALERT", "when": "true", "then": { "decision": "'NO_ALERT'" } }
  ]
}
```

Với danh sách theo dõi `["Van An Nguyen", "Tran Van Binh"]`, khách hàng `NGUYỄN Văn An` khớp `WATCHLIST_HIT` với `matchScore` 1.0000, còn khách hàng `Le Thi Hoa` khớp `NO_ALERT`. Bảng này được bộ kiểm thử chạy thật.

Ghi chú về bảng:

- **Tên trống.** Luật đầu tiên bắt trường hợp tên bị thiếu hoặc để trống. Nếu không có luật này, một tên trống sẽ được 1 điểm khi so với một mục trống trong danh sách và 0 điểm với mọi mục khác, nên một khách hàng không có tên sẽ âm thầm lọt qua.
- **Trả về điểm số.** `matchScore` cho chuyên viên rà soát cảnh báo thấy mức độ khớp gần đến đâu.
- **`maxNameSimilarity` hay `exists`.** Cùng điều kiện đó có thể viết dưới dạng comprehension:

  ```
  watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))
  ```

  Nên dùng `maxNameSimilarity`: hàm này chuẩn hoá tên khách hàng một lần thay vì một lần cho mỗi mục, trả về điểm số, và không chịu giới hạn số vòng lặp của comprehension (xem [Hiệu năng và giới hạn](#hiệu-năng-và-giới-hạn)). Hãy dùng `exists` khi danh sách chứa các struct và điều kiện liên quan đến các trường khác:

  ```
  watchlist.exists(w, nameSimilarity(w.name, customer.fullName) >= decimal('0.85') && w.birthYear == customer.birthYear)
  ```

## Chọn ngưỡng

Ngưỡng là sự đánh đổi giữa **âm tính giả** (một người có trong danh sách không bị gắn cờ) và **dương tính giả** (một khách hàng không liên quan bị gắn cờ và chuyên viên phải xử lý cảnh báo). Không có giá trị nào đúng cho mọi trường hợp.

- Ngưỡng thấp hơn bắt được nhiều biến thể chính tả hơn, nhưng sinh nhiều cảnh báo hơn.
- Ngưỡng cao hơn sinh ít cảnh báo hơn, nhưng bỏ sót nhiều biến thể hơn.

Tên tiếng Việt cần được chú ý đặc biệt. Một số ít họ (Nguyễn, Trần, Lê, Phạm) và tên đệm (Văn, Thị) được một phần lớn dân số dùng chung, nên những người khác nhau thường có tên rất giống nhau, và điểm thưởng tiền tố của Winkler lại ưu ái việc trùng họ. Ví dụ `similarity('Nguyen Van An', 'Nguyen Van Binh')` là `0.9446`: với ngưỡng 0.92, hai người khác nhau này bị coi là khớp. Ngược lại, `similarity('Mohammed Ali', 'Muhammad Ali')` là `0.8700` và sẽ bị bỏ sót ở ngưỡng 0.92, trong khi `nameSimilarity` cho `0.9333`.

Để chọn ngưỡng:

1. Xây dựng một mẫu đã gán nhãn từ chính dữ liệu của bạn: các cặp tên biết chắc là cùng một người (gồm cả các biến thể chính tả thực tế, thiếu dấu, đảo thứ tự, viết tắt như `T.` cho `Thị`) và các cặp biết chắc là những người khác nhau có tên gần giống.
2. Tính điểm bằng RuleStone và, với mỗi ngưỡng ứng viên, đếm số trường hợp khớp đúng được phát hiện và số cảnh báo sai được sinh ra.
3. Chọn ngưỡng đáp ứng yêu cầu phát hiện trong chính sách của bạn với khối lượng cảnh báo mà đội ngũ có thể rà soát, và lập hồ sơ cho quyết định cùng với mẫu dữ liệu.
4. Kiểm thử lại khi danh sách, tập khách hàng hoặc hàm này thay đổi.

Hãy kết hợp điểm tên với các thuộc tính khác khi có (ngày sinh, quốc tịch, số giấy tờ tuỳ thân), như trong ví dụ cuối của [Viết luật sàng lọc](#viết-luật-sàng-lọc). Ngưỡng tên thấp hơn kết hợp với ngày sinh trùng khớp thường chính xác hơn nhiều so với chỉ dùng điểm tên.

## Ví dụ tính toán

Mọi dòng của bảng này đều được bộ kiểm thử đánh giá (`FuzzyLibraryTest`), và mọi điểm số nêu trong trang này đều xuất hiện ở đây.

| Biểu thức | Kết quả |
|---|---|
| `normalizeName('  NGUYỄN  Văn Đức, Jr. ')` | `'nguyen van duc jr'` |
| `normalizeName('Đặng Thị Ngọc Hân')` | `'dang thi ngoc han'` |
| `normalizeName('José Müller-Lüdenscheidt')` | `'jose muller ludenscheidt'` |
| `normalizeName('ACME Ltd. No.7')` | `'acme ltd no 7'` |
| `similarity('MARTHA', 'MARHTA')` | `0.9611` |
| `similarity('DWAYNE', 'DUANE')` | `0.8400` |
| `similarity('DIXON', 'DICKSONX')` | `0.8133` |
| `similarity('abcxyz', 'abcuvw')` | `0.6667` |
| `similarity('Nguyễn Văn An', 'Nguyen Van An')` | `1.0000` |
| `similarity('Nguyen Van An', 'Nguyen Van Anh')` | `0.9857` |
| `similarity('Tran Thi Hoa', 'Tran Thi Hoai')` | `0.9846` |
| `similarity('Nguyen Van An', 'Nguyen Van Binh')` | `0.9446` |
| `similarity('Nguyen Thi Minh Khai', 'Nguyen T. Minh Khai')` | `0.9244` |
| `similarity('Mohamed', 'Mohammed')` | `0.9750` |
| `similarity('Mohammed Ali', 'Muhammad Ali')` | `0.8700` |
| `similarity('Nguyen Van An', 'Van An Nguyen')` | `0.5971` |
| `similarity('Nguyen Van An', 'Le Thi Hoa')` | `0.4859` |
| `similarity('', '-')` | `1.0000` |
| `similarity('Nguyen', '')` | `0.0000` |
| `nameSimilarity('Nguyen Van An', 'Van An Nguyen')` | `1.0000` |
| `nameSimilarity('Smith, John', 'JOHN SMITH')` | `1.0000` |
| `nameSimilarity('Ngô Bảo Châu', 'Châu Ngô Bảo')` | `1.0000` |
| `nameSimilarity('Nguyen Thi Minh Khai', 'Nguyen T. Minh Khai')` | `0.9800` |
| `nameSimilarity('Mohammed Ali', 'Muhammad Ali')` | `0.9333` |
| `nameSimilarity('Nguyen Van An', 'Tran Van Binh')` | `0.6447` |
| `levenshtein('kitten', 'sitting')` | `3` |
| `levenshtein('Lê Văn Tám', 'Le Van Tam')` | `0` |
| `levenshtein('Nguyen Van An', 'Nguyen Van Anh')` | `1` |
| `levenshtein('Nguyen Van An', 'Van An Nguyen')` | `10` |
| `maxNameSimilarity('Nguyễn Văn An', ['Tran Van Binh', 'Van An Nguyen'])` | `1.0000` |
| `maxNameSimilarity('Nguyen Van An', ['Tran Van Binh', 'Le Thi Hoa'])` | `0.6447` |
| `maxNameSimilarity('Nguyen Van An', [])` | `0.0000` |
| `maxNameSimilarity('Nguyen Van An', ['Nguyen Van Binh']) >= decimal('0.92')` | `true` |
| `['Tran Van Binh', 'Van An Nguyen'].exists(w, nameSimilarity(w, 'NGUYỄN Văn An') >= decimal('0.92'))` | `true` |

`MARTHA`/`MARHTA`, `DWAYNE`/`DUANE` và `DIXON`/`DICKSONX` là các cặp tham chiếu được công bố cùng thuật toán Jaro-Winkler.

## Hiệu năng và giới hạn

**Chi phí mỗi lần so sánh.** Với hai tên có độ dài *n* và *k*:

- chuẩn hoá tỷ lệ tuyến tính với độ dài văn bản;
- `similarity` tối đa tỷ lệ với *n* × *k*, và trên thực tế thấp hơn nhiều, vì các ký tự chỉ được so sánh trong cửa sổ khớp;
- `nameSimilarity` tốn khoảng hai lần gọi `similarity` cộng với việc sắp xếp các từ;
- `levenshtein` tỷ lệ với *n* × *k* và dùng bộ nhớ tỷ lệ với độ dài của tên ngắn hơn.

Với các tên thông thường dài 10 đến 40 ký tự, một lần so sánh `nameSimilarity` mất vài micro giây trên một máy tính xách tay hiện nay, nên `maxNameSimilarity` trên danh sách 10.000 tên mất cỡ vài chục mili giây. `maxNameSimilarity` thực hiện một lần `nameSimilarity` cho mỗi phần tử của danh sách và dừng sớm khi đạt điểm tuyệt đối, nên chi phí tăng tuyến tính theo độ dài danh sách. Các mục trong danh sách được chuẩn hoá lại ở mỗi lần đánh giá; RuleStone không lưu đệm chúng giữa các lần gọi. Với danh sách hàng nghìn tên được đánh giá ở mọi giao dịch, hãy đo độ trễ trong môi trường của bạn và cân nhắc dùng một dịch vụ sàng lọc chuyên dụng (xem [Hạn chế](#hạn-chế)).

**Giới hạn.** Để bảo vệ engine khỏi đầu vào quá lớn:

| Giới hạn | Giá trị | Áp dụng cho |
|---|---|---|
| Kích thước danh sách | 100.000 phần tử | `maxNameSimilarity`. Danh sách dài hơn làm việc đánh giá thất bại. |
| Độ dài tên | 1.000 ký tự sau khi chuẩn hoá | Mọi tham số của `similarity`, `nameSimilarity`, `levenshtein` và `maxNameSimilarity`, kể cả các phần tử của danh sách. Tên dài hơn làm việc đánh giá thất bại. `normalizeName` không có giới hạn. |
| Số vòng lặp của comprehension | mặc định 10.000 | `watchlist.exists(...)` và các macro khác trên danh sách. Danh sách dài hơn làm việc đánh giá thất bại. Thay đổi bằng `RuleStone.builder().maxIterations(n)`. |

`maxNameSimilarity` là một lần gọi hàm, không phải comprehension, nên giới hạn số vòng lặp không áp dụng cho nó: với thiết lập mặc định, hàm này nhận danh sách tới 100.000 tên.

## Lỗi

Các lỗi sau làm việc đánh giá thất bại, và do đó làm thất bại toàn bộ quyết định (xem [Lỗi](expressions.md#lỗi) trong tài liệu tham khảo ngôn ngữ biểu thức):

- `maxNameSimilarity() list must not have more than 100000 elements`
- `fuzzy matching arguments must not be longer than 1000 characters after normalization`

Thông báo lỗi không bao giờ chứa các tên đang được so sánh, nên có thể ghi log mà không làm lộ dữ liệu khách hàng.

Việc truyền một tham số không phải `string`, một danh sách không phải danh sách chuỗi, hoặc so sánh điểm số với một `double` như `0.92` đều được báo lỗi khi nạp luật.

## Hạn chế

- **Không phải hệ thống sàng lọc được chứng nhận.** Các hàm này không thay thế sản phẩm sàng lọc danh sách cấm vận hay PEP, các danh sách chính thức, hay nghĩa vụ trong chương trình phòng, chống rửa tiền và tài trợ khủng bố (AML/CFT) của tổ chức bạn. Chúng không quản lý danh sách, bí danh, quan hệ giữa các thực thể hay dấu vết kiểm toán của các phiên bản danh sách.
- **Không chuyển tự.** Tên ở các hệ chữ viết khác nhau không được liên hệ với nhau: `Путин` viết bằng chữ Kirin, `محمد` viết bằng chữ Ả Rập hay chữ Hán không khớp với dạng chuyển tự Latin của chúng, và các cách chuyển tự khác nhau (`Mohammed`, `Muhammad`, `Mohamad`) chỉ khớp ở mức độ mà cách viết của chúng giống nhau. Nếu cần so khớp giữa các hệ chữ viết, hãy chuyển tự cả hai bên về cùng một cách trước khi gọi các hàm này.
- **Không xét ngữ âm, tên gọi thân mật hay danh xưng.** `Bill` không khớp với `William`. Danh xưng và hậu tố (`Mr`, `Jr`, `Ltd`) được giữ như các từ và ảnh hưởng tới điểm số; hãy loại bỏ chúng trước khi so sánh nếu chúng không có ý nghĩa với bạn.
- **Chữ cái không có dạng phân rã.** Ngoài `đ`, các chữ cái như `ø`, `ł`, `æ` và `ß` không được ánh xạ sang chữ Latin.
- **Điểm số không phải xác suất.** `0.95` không có nghĩa là 95% khả năng là cùng một người. Điểm số phụ thuộc vào độ dài và cấu trúc của tên; một ngưỡng chỉ có ý nghĩa sau khi được kiểm thử trên chính dữ liệu của bạn.
- **Bắt buộc rà soát.** Hãy coi một kết quả khớp là cảnh báo cần người rà soát theo chính sách AML của bạn, không bao giờ là kết luận tự động, và coi việc không có kết quả khớp là "luật này không đưa ra cảnh báo", chứ không phải là đã được xác minh an toàn.
