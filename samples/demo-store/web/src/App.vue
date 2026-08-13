<template>
  <div class="orders">
    <h1>订单演示</h1>
    <ul>
      <li v-for="o in orders" :key="o.id">{{ o.id }}</li>
    </ul>
  </div>
</template>

<script>
export default {
  data() {
    return { orders: [] };
  },
  mounted() {
    this.load();
  },
  methods: {
    load() {
      this.$http.get('/api/orders').then((r) => {
        this.orders = r.data;
      });
    },
  },
};
</script>
