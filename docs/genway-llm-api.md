Generation API — hướng dẫn tích hợp cho các service khác
Tài liệu này dành cho backend team khác (tenant) muốn gọi sang Genway để dùng model AI của bên thứ 3 (RunningHub, fal.ai, Anthropic, OpenAI, VoyageAI, ...) mà không cần biết provider thật đứng sau, không cần tự cầm credential của provider.

Muốn xem cách quản trị (tạo tenant, cấp quyền provider, thêm model...) xem admin-api.md — tài liệu đó dành cho người vận hành Genway, không dành cho tenant.

## 1. Lấy API key
Genway không tự đăng ký được — liên hệ người quản trị Genway để:

- Tạo 1 tenant đại diện cho service của bạn.
- Cấp quyền dùng 1 hoặc nhiều provider cụ thể (vd fal, runninghub, openai...) cho tenant đó.
- Issue 1 API key (dạng gw_xxxxxxxx...) — key chỉ hiện đúng 1 lần lúc tạo, không có endpoint "xem lại". Lưu ngay vào secret manager/env của service bạn.

Nếu gọi model của provider mà tenant chưa được cấp quyền, Genway trả 403 UNAUTHORIZED dù key hợp lệ — báo lại cho quản trị Genway để cấp thêm quyền, không phải lỗi phía bạn.

## 2. Endpoint chính
```
POST /api/generation
Header: Authorization: Bearer <api-key-cua-ban>
Header (optional): Idempotency-Key: <chuoi-bat-ky, nen la uuid>
Content-Type: application/json
```

### Request body — 1 envelope chuẩn cho mọi provider/model
```json
{
  "model_key": "gpt-image-2",
  "call_type": "image",
  "input": { "prompt": "a cat wearing sunglasses", "size": "1024x1024" },
  "options": { "num_outputs": 1, "metadata": { "internal_order_id": "abc123" } }
}
```

| Field | Bắt buộc | Ghi chú |
|---|---|---|
| model_key | ✅ | Định danh model, không phải tên provider — Genway tự tra ra provider/credential thật đứng sau. Xem bảng model ở §4. |
| call_type | ✅ | text \| image \| video \| embedding \| rerank — phải khớp đúng call_type mà model_key đó được cấu hình, sai sẽ bị từ chối ngay (400). |
| input | ✅ | Nội dung tuỳ theo call_type, xem §3. |
| options | ❌ | metadata (echo lại, không dùng để xử lý gì); idempotency_key (thay thế cho header Idempotency-Key nếu bạn không set header được); webhook_url — hiện được nhận và lưu nhưng KHÔNG được gọi lại cho tenant, chỉ dùng nội bộ cho 1 provider. Đừng dựa vào webhook — luôn dùng polling (§5) để lấy kết quả. |

### Input theo từng call_type
image / video — cần input.prompt (bắt buộc, không rỗng). Các field khác (size, reference_images, width, height, seconds, strength...) tuỳ model, xem mô tả model cụ thể từ quản trị Genway hoặc GET /api/admin/model-configs/{id} (field inputMapping.fields[].standard_field cho biết model đó đọc field nào trong input).

```json
{ "input": { "prompt": "...", "reference_images": ["https://.../ref.jpg"], "width": 1024, "height": 1024 } }
```

text — cần input.messages là mảng không rỗng (dạng chat message, tuỳ model — vd OpenAI/Anthropic đều dùng [{ "role": "user", "content": "..." }]).

```json
{ "input": { "messages": [{ "role": "user", "content": "Tóm tắt đoạn văn sau..." }], "max_tokens": 1024 } }
```

embedding — cần input.texts là mảng không rỗng. Với model embedding "phẳng" thông thường đây là mảng chuỗi; một số model đặc biệt (chunk embedding, multimodal embedding — xem §6) dùng input.texts để chứa cấu trúc JSON khác (mảng lồng mảng, hoặc mảng object) theo đúng shape thật của provider đó — Genway chỉ forward nguyên JSON bạn đưa vào, không tự validate hay convert shape, nên đọc kỹ mô tả model cụ thể trước khi gọi loại này.

```json
{ "input": { "texts": ["đoạn văn bản 1", "đoạn văn bản 2"] } }
```

rerank — cần input.query (chuỗi không rỗng) và input.documents (mảng chuỗi không rỗng).

```json
{ "input": { "query": "tài liệu về hợp đồng thuê nhà", "documents": ["văn bản 1", "văn bản 2"], "top_k": 5 } }
```

### Response — luôn là 1 trong 3 dạng
Xong ngay (đa số model text/embedding, hoặc image/video cấu hình sync):

```json
{
  "success": true,
  "requestId": "b7e1a2c0-....",
  "status": "success",
  "data": {
    "response": null,
    "outputs": [
      { "publicUrl": "https://cdn.../out.png", "mediaType": "image", "mimeType": "image/png", "sizeBytes": 182004, "width": 1024, "height": 1024, "durationSeconds": null }
    ]
  }
}
```

- data.response: kết quả cho text/embedding (nội dung trả về trực tiếp từ model; embedding > ~50KB bị rút gọn thành { "dimensions": N, "count": M } để tránh payload quá lớn).
- data.outputs: chỉ có với image/video — file đã được Genway tải về và lưu ở storage riêng (publicUrl là URL ổn định lâu dài, không phải URL gốc từ provider vốn có thể hết hạn).

Đang xử lý (model chạy dạng hàng đợi — ComfyUI/RunningHub và một số model video):

```json
{ "success": true, "requestId": "b7e1a2c0-....", "status": "processing", "pollUrl": "/api/generation/b7e1a2c0-...." }
```

→ dùng pollUrl (hoặc tự ghép GET /api/generation/{requestId}) để hỏi lại kết quả, xem §5.

Lỗi:

```json
{ "success": false, "requestId": "b7e1a2c0-....", "errorCode": "PROVIDER_ERROR", "message": "Provider returned 500" }
```

requestId vẫn có ngay cả khi lỗi xảy ra sau khi request đã được ghi nhận (lỗi từ provider); các lỗi bị từ chối trước khi tạo request (sai model_key, input sai shape, token sai...) thì requestId có thể là null.

## 3. Bảng errorCode + HTTP status tương ứng

| errorCode | HTTP status | Ý nghĩa | Tenant nên làm gì |
|---|---|---|---|
| UNAUTHORIZED | 401 | Thiếu/sai/đã revoke API key | Kiểm tra lại header Authorization |
| UNAUTHORIZED (Forbidden) | 403 | Key hợp lệ nhưng tenant chưa được cấp quyền provider của model này | Liên hệ quản trị Genway cấp quyền |
| INVALID_PARAMS | 400 / 404 | model_key không tồn tại/không active, call_type không khớp model, input thiếu field bắt buộc | Sửa request, không nên retry nguyên trạng |
| PROVIDER_ERROR | thường 200 (lỗi nằm trong errorCode, không phải HTTP status) | Provider trả lỗi sau khi Genway đã tự retry hết số lần cấu hình | Có thể thử lại sau, hoặc báo quản trị Genway nếu lặp lại liên tục |
| PROVIDER_TIMEOUT | như trên | Lỗi mạng/timeout gọi provider, đã retry hết lần | Thử lại sau |
| PROVIDER_BILLING_ERROR | như trên (thường ứng với 402 từ provider) | Tài khoản của Genway bên phía provider hết credit/balance | Báo quản trị Genway nạp thêm balance, retry không giúp được |
| RATE_LIMITED | như trên | Provider trả 429 liên tục, đã retry hết lần | Giãn tần suất gọi, thử lại sau |
| INTERNAL_ERROR | 500 | Lỗi phía Genway | Báo quản trị Genway |

Genway đã tự động retry các lỗi 429/5xx từ provider theo cấu hình của connection (mặc định tối đa 3 lần, backoff tăng dần) trước khi trả lỗi về cho bạn — không cần tự retry ngay lập tức, nếu vẫn thấy PROVIDER_ERROR/RATE_LIMITED nghĩa là Genway đã thử và vẫn fail.

## 4. Idempotency
Gửi kèm header Idempotency-Key: <giá-trị-do-bạn-tự-sinh> (khuyến khích dùng UUID, gắn với 1 lần submit logic ở phía bạn). Gọi lại POST /api/generation với cùng key đó sẽ trả về đúng trạng thái hiện tại của request gốc thay vì tạo request mới — dùng khi bạn cần retry an toàn ở tầng network (timeout, mất kết nối) mà không sợ tạo trùng job. Không truyền được thì có thể đặt options.idempotency_key trong body thay thế.

Lưu ý: key đã dùng bởi tenant khác sẽ bị từ chối (400) — key chỉ nên unique trong phạm vi tenant của chính bạn, nhưng để an toàn hãy tự thêm prefix riêng (vd tên service của bạn) khi sinh key.

## 5. Polling kết quả (status: "processing")
```
GET /api/generation/{requestId}
Header: Authorization: Bearer <api-key-cua-ban>
```
Trả về đúng 1 trong 3 dạng như §2, phản ánh trạng thái mới nhất. Model chạy queue (RunningHub/ComfyUI, một số model video) thường mất vài chục giây tới vài phút — nên poll theo chu kỳ, không hỏi dồn dập (gợi ý: bắt đầu 3-5s rồi tăng dần, hoặc cố định 10-20s). Request chỉ thuộc về tenant đã tạo ra nó — gọi GET bằng key của tenant khác cho cùng 1 requestId sẽ nhận 404, không lộ dữ liệu chéo tenant.

## 6. Model hiện có (ví dụ tại thời điểm viết tài liệu)
Danh sách model thay đổi theo thời gian do quản trị Genway thêm/bớt qua GET /api/admin/model-configs — bảng dưới đây chỉ là ảnh chụp tham khảo, luôn hỏi quản trị Genway hoặc gọi endpoint admin đó để lấy danh sách mới nhất trước khi tích hợp cứng model_key vào code.

| model_key | Provider | call_type | Đồng bộ? |
|---|---|---|---|
| claude-sonnet-5 | anthropic | text | sync |
| deepseek-flash | deepseek | text (hỗ trợ reasoning toggle — xem ghi chú dưới bảng) | sync |
| deepseek-v4-pro | deepseek | text (hỗ trợ reasoning toggle — xem ghi chú dưới bảng) | sync |
| voyage-3-large | voyageai | embedding | sync |
| voyage-4-large | voyageai | embedding | sync |
| voyage-4 | voyageai | embedding | sync |
| voyage-4-lite | voyageai | embedding | sync |
| voyage-code-3 | voyageai | embedding | sync |
| voyage-context-3 | voyageai | embedding (chunk, shape đặc biệt — xem §3) | sync |
| voyage-multimodal-3.5 | voyageai | embedding (multimodal, shape đặc biệt — xem §3) | sync |
| voyage-multimodal-3 | voyageai | embedding (multimodal, shape đặc biệt — xem §3) | sync |
| rerank-2.5 | voyageai | rerank | sync |
| rerank-2.5-lite | voyageai | rerank | sync |
| flux-img2img-v1 | fal | image | sync |
| gpt-image-2 | openai | image | sync |
| gpt-4o | openai | text | sync |
| runninghub-img2img-v1 | runninghub | image | queue (ComfyUI) |
| runninghub-txt2img-v1 | runninghub | image | queue (ComfyUI) |
| runninghub-txt2vid-v1 | runninghub | video | queue (ComfyUI) |
| fal-video-v1 | fal | video | queue |

Nhớ: bạn chỉ gọi được model thuộc provider mà tenant của bạn đã được cấp quyền (§1) — có trong bảng này không đồng nghĩa bạn được phép gọi.

Ghi chú deepseek-flash/deepseek-v4-pro: ngoài input.messages bắt buộc như mọi model text khác, 2 model này còn nhận thêm các field optional forward thẳng sang DeepSeek: input.thinking ({ "type": "enabled" | "disabled" } — bật/tắt chế độ reasoning), input.reasoning_effort ("none" | "low" | "high" | "max"), input.max_tokens, input.temperature, input.top_p, input.response_format, input.stop, input.tools, input.tool_choice. Khi thinking bật, model thật có sinh thêm phần suy luận (reasoning_content) trước câu trả lời cuối, nhưng Genway chỉ trả về đúng câu trả lời cuối (data.response), không trả phần suy luận đó.

```json
{
  "model_key": "deepseek-flash",
  "call_type": "text",
  "input": {
    "messages": [{ "role": "user", "content": "Giải thích ngắn gọn thuyết tương đối hẹp" }],
    "thinking": { "type": "enabled" },
    "reasoning_effort": "low",
    "max_tokens": 2048
  }
}
```

## 7. Ví dụ đầy đủ (curl)
Text, sync:

```bash
curl -X POST https://genway.tho2kdev.com/api/generation \
  -H "Authorization: Bearer gw_xxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f6a2b1e-6e2b-4e9b-9a3e-2f1b6a2e6e2b" \
  -d '{
    "model_key": "gpt-4o",
    "call_type": "text",
    "input": { "messages": [{ "role": "user", "content": "Xin chào" }], "max_tokens": 256 }
  }'
```

Rerank, sync:

```bash
curl -X POST https://genway.tho2kdev.com/api/generation \
  -H "Authorization: Bearer gw_xxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model_key": "rerank-2.5-lite",
    "call_type": "rerank",
    "input": {
      "query": "tài liệu về hợp đồng thuê nhà",
      "documents": ["văn bản A về thuê nhà", "văn bản B về mua bán ô tô"],
      "top_k": 1
    }
  }'
# -> data.response = { "object": "list", "data": [{ "index": 0, "relevance_score": 0.87 }], ... }
```

Image, chạy dạng hàng đợi (RunningHub/ComfyUI) — submit rồi poll:

```bash
# 1. Submit
curl -X POST https://genway.tho2kdev.com/api/generation \
  -H "Authorization: Bearer gw_xxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model_key": "runninghub-txt2img-v1",
    "call_type": "image",
    "input": { "prompt": "a lighthouse at sunset", "width": 1024, "height": 1024 }
  }'
# -> { "success": true, "requestId": "...", "status": "processing", "pollUrl": "/api/generation/..." }

# 2. Poll lại vài giây/lần cho tới khi status = "success" hoặc success=false
curl https://genway.tho2kdev.com/api/generation/<requestId> \
  -H "Authorization: Bearer gw_xxxxxxxxxxxxxxxxxxxx"
```

## 8. Câu hỏi thường gặp
- Gọi bằng model chưa được cấp quyền có tạo được request không? Không — bị chặn ở bước xác thực, trả 403 ngay, không tốn lượt gọi provider thật.
- Muốn dùng model mới mà chưa thấy trong bảng §6? Báo quản trị Genway — nếu provider đó tenant bạn đã có quyền thì chỉ cần thêm 1 model_config (không cần deploy code mới); nếu là provider mới hoàn toàn thì cần thêm cả provider + provider_connection (credential) trước.
- File ảnh/video trả về (outputs[].publicUrl) có tồn tại vĩnh viễn không? Đây là URL do storage của Genway phục vụ (không phải link tạm của provider) — hỏi quản trị Genway về chính sách lưu trữ/ retention cụ thể nếu cần đảm bảo lâu dài.
