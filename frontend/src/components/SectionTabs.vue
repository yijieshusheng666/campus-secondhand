<script setup>
import { useRouter } from 'vue-router'

defineProps({ active: { type: String, required: true } })

const router = useRouter()
const tabs = [
  { key: 'mine', label: '我要求购', path: '/wanted' },
  { key: 'square', label: '求购广场', path: '/wanted/square' }
]
</script>

<template>
  <div class="tabs">
    <div v-for="t in tabs" :key="t.key" class="tab" :class="{ active: t.key === active }"
      @click="router.push(t.path)">{{ t.label }}</div>
  </div>
</template>

<style scoped>
.tabs {
  display: inline-flex;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 20px;
  padding: 3px;
  margin-bottom: 16px;
}
.tab {
  padding: 6px 20px;
  font-size: 13px;
  color: #888;
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}
.tab:hover { color: #555; }
.tab.active { background: #ffd21e; color: #23201a; font-weight: 600; }
</style>
