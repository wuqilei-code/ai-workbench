INSERT INTO chat_session (user_id, agent_session_id, title, agent, skills, mcp_servers, created_at, updated_at)
VALUES (47, 'mock-order-test-001', '模拟订单服务测试', 'chat', '[]', '["order-service"]', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE title=VALUES(title);
SELECT id, title, mcp_servers FROM chat_session WHERE agent_session_id='mock-order-test-001';
