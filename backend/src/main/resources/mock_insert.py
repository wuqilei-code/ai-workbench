import json, sys
config = [
    {"name":"list_orders","method":"GET","path":"/api/orders","description":"查询订单列表，支持 status 和 user_id 筛选","params":[{"name":"status","in":"query","type":"string","description":"订单状态筛选: pending/paid/shipped"},{"name":"user_id","in":"query","type":"string","description":"用户ID筛选"}]},
    {"name":"get_order","method":"GET","path":"/api/orders/{id}","description":"根据订单ID查询订单详情","params":[{"name":"id","in":"path","type":"string","required":True,"description":"订单ID，如 ORD-001"}]},
    {"name":"create_order","method":"POST","path":"/api/orders","description":"创建新订单","body_fields":[{"name":"product","type":"string","required":True,"description":"商品名称"},{"name":"quantity","type":"integer","required":True,"description":"购买数量"},{"name":"price","type":"number","required":True,"description":"单价"},{"name":"user_id","type":"string","required":True,"description":"用户ID"}]},
    {"name":"update_order","method":"PUT","path":"/api/orders/{id}","description":"更新订单信息","params":[{"name":"id","in":"path","type":"string","required":True,"description":"订单ID"}],"body_fields":[{"name":"product","type":"string","description":"商品名称"},{"name":"quantity","type":"integer","description":"数量"},{"name":"price","type":"number","description":"价格"},{"name":"status","type":"string","description":"状态: pending/paid/shipped"}]},
    {"name":"delete_order","method":"DELETE","path":"/api/orders/{id}","description":"删除指定订单","params":[{"name":"id","in":"path","type":"string","required":True,"description":"订单ID"}]},
    {"name":"list_products","method":"GET","path":"/api/products","description":"查询商品列表，支持按分类筛选","params":[{"name":"category","in":"query","type":"string","description":"分类筛选: 手机/电脑/配件/平板/手表"}]},
    {"name":"get_user","method":"GET","path":"/api/users/{id}","description":"根据用户ID查询用户信息","params":[{"name":"id","in":"path","type":"string","required":True,"description":"用户ID，如 U1001"}]}
]
print(json.dumps(config, ensure_ascii=False))
