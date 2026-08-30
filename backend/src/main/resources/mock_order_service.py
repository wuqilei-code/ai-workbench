"""模拟订单管理服务 - 用于 MCP 手动接口测试。

启动: uvicorn mock_order_service:app --host 0.0.0.0 --port 9000
"""
import uuid
from datetime import datetime
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

ORDERS = {
    "ORD-001": {"id": "ORD-001", "product": "iPhone 16 Pro", "quantity": 1, "price": 8999.0, "status": "paid", "user_id": "U1001", "created_at": "2026-08-28 10:00:00"},
    "ORD-002": {"id": "ORD-002", "product": "MacBook Air M4", "quantity": 1, "price": 12999.0, "status": "shipped", "user_id": "U1002", "created_at": "2026-08-29 14:30:00"},
    "ORD-003": {"id": "ORD-003", "product": "AirPods Pro 3", "quantity": 2, "price": 1899.0, "status": "pending", "user_id": "U1001", "created_at": "2026-08-30 09:15:00"},
}

PRODUCTS = [
    {"id": "P001", "name": "iPhone 16 Pro", "category": "手机", "price": 8999.0, "stock": 50},
    {"id": "P002", "name": "MacBook Air M4", "category": "电脑", "price": 12999.0, "stock": 30},
    {"id": "P003", "name": "AirPods Pro 3", "category": "配件", "price": 1899.0, "stock": 200},
    {"id": "P004", "name": "iPad Pro M4", "category": "平板", "price": 8999.0, "stock": 40},
    {"id": "P005", "name": "Apple Watch Ultra 3", "category": "手表", "price": 5999.0, "stock": 60},
]

USERS = {
    "U1001": {"id": "U1001", "name": "张三", "email": "zhangsan@example.com", "level": "gold"},
    "U1002": {"id": "U1002", "name": "李四", "email": "lisi@example.com", "level": "silver"},
    "U1003": {"id": "U1003", "name": "王五", "email": "wangwu@example.com", "level": "normal"},
}


# ---------- handlers ----------

async def list_orders(request: Request):
    status = request.query_params.get("status")
    user_id = request.query_params.get("user_id")
    result = list(ORDERS.values())
    if status:
        result = [o for o in result if o["status"] == status]
    if user_id:
        result = [o for o in result if o["user_id"] == user_id]
    return JSONResponse({"total": len(result), "orders": result})


async def get_order(request: Request):
    oid = request.path_params["id"]
    order = ORDERS.get(oid)
    if not order:
        return JSONResponse({"error": "order not found"}, status_code=404)
    return JSONResponse(order)


async def create_order(request: Request):
    body = await request.json()
    oid = f"ORD-{uuid.uuid4().hex[:6].upper()}"
    order = {
        "id": oid,
        "product": body.get("product", ""),
        "quantity": body.get("quantity", 1),
        "price": body.get("price", 0),
        "status": "pending",
        "user_id": body.get("user_id", ""),
        "created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }
    ORDERS[oid] = order
    return JSONResponse(order, status_code=201)


async def update_order(request: Request):
    oid = request.path_params["id"]
    order = ORDERS.get(oid)
    if not order:
        return JSONResponse({"error": "order not found"}, status_code=404)
    body = await request.json()
    for k in ("product", "quantity", "price", "status"):
        if k in body:
            order[k] = body[k]
    return JSONResponse(order)


async def delete_order(request: Request):
    oid = request.path_params["id"]
    if oid not in ORDERS:
        return JSONResponse({"error": "order not found"}, status_code=404)
    del ORDERS[oid]
    return JSONResponse({"deleted": oid})


async def list_products(request: Request):
    category = request.query_params.get("category")
    result = PRODUCTS
    if category:
        result = [p for p in result if p["category"] == category]
    return JSONResponse({"total": len(result), "products": result})


async def get_user(request: Request):
    uid = request.path_params["id"]
    user = USERS.get(uid)
    if not user:
        return JSONResponse({"error": "user not found"}, status_code=404)
    return JSONResponse(user)


# ---------- app ----------

app = Starlette(routes=[
    Route("/api/orders", list_orders, methods=["GET"]),
    Route("/api/orders", create_order, methods=["POST"]),
    Route("/api/orders/{id}", get_order, methods=["GET"]),
    Route("/api/orders/{id}", update_order, methods=["PUT"]),
    Route("/api/orders/{id}", delete_order, methods=["DELETE"]),
    Route("/api/products", list_products, methods=["GET"]),
    Route("/api/users/{id}", get_user, methods=["GET"]),
])
