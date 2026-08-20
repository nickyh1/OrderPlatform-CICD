-- V2__init_test_data.sql

INSERT INTO product (name, price, description, status) VALUES
('iPhone 16 Pro', 8999.00, 'Apple iPhone 16 Pro 256GB', 1),
('MacBook Air M3', 9499.00, 'Apple MacBook Air 15" M3', 1),
('AirPods Pro 2', 1899.00, 'Apple AirPods Pro 2nd Gen', 1),
('Sony WH-1000XM5', 2499.00, 'Sony noise-cancelling headphones', 1),
('Kindle Paperwhite', 999.00, 'Amazon Kindle Paperwhite 2024', 1);

INSERT INTO inventory (product_id, total_stock, available_stock, locked_stock) VALUES
(1, 100, 100, 0),
(2, 50, 50, 0),
(3, 200, 200, 0),
(4, 80, 80, 0),
(5, 150, 150, 0);