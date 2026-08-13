from repository import OrderRepository


class OrderService:
    def __init__(self):
        self.repo = OrderRepository()

    def create(self, user_id, sku, qty):
        return self.repo.insert(user_id, sku, qty)
