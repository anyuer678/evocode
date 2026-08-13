package com.demo.store;

public class OrderService {
    private final OrderRepository orderRepo;
    private final PaymentService paymentService;

    public OrderService(OrderRepository r, PaymentService p) {
        this.orderRepo = r;
        this.paymentService = p;
    }

    public Object create(long userId, long sku, int qty) {
        long orderId = orderRepo.insert(userId, sku, qty);
        return paymentService.charge(orderId);
    }

    public Object findById(long orderId) {
        return orderRepo.select(orderId);
    }
}
