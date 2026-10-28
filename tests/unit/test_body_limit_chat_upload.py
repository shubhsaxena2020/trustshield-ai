import asyncio

from services.api.security import BodyLimitMiddleware


def test_multi_attachment_chat_body_budget_does_not_expand_other_endpoints():
    async def downstream(scope, receive, send):
        await receive()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    async def request(path):
        body = b"123456"
        scope = {"type": "http", "method": "POST", "path": path,
                 "headers": [(b"content-length", str(len(body)).encode())]}
        sent = []

        async def receive():
            return {"type": "http.request", "body": body, "more_body": False}

        async def send(message):
            sent.append(message)

        await BodyLimitMiddleware(downstream, max_bytes=4, chat_upload_max_bytes=8)(scope, receive, send)
        return sent[0]["status"]

    assert asyncio.run(request("/api/v1/chat/upload")) == 200
    assert asyncio.run(request("/api/v1/chat")) == 413
