package com.demo.store;

public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService s) {
        this.orderService = s;
    }

    public Object create(long userId, long sku, int qty) {
        return orderService.create(userId, sku, qty);
    }

    public Object query(long orderId) {
        return orderService.findById(orderId);
    }
}
