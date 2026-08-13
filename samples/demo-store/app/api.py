from service import OrderService


class OrderApi:
    def __init__(self):
        self.service = OrderService()

    def create(self, user_id, sku, qty):
        return self.service.create(user_id, sku, qty)
