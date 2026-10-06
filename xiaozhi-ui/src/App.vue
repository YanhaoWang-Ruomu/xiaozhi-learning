<script setup>
import ChatWindow from '@/components/ChatWindow.vue'
</script>

<template>
  <ChatWindow />
</template>

<style>
* { box-sizing: border-box; }
html, body, #app { margin: 0; min-width: 320px; min-height: 100%; }
body {
  font-family: 'Microsoft YaHei', system-ui, sans-serif;
  color: #26374b;
  background: #f4f7fb;
  line-height: 1.6;
}
button, input, textarea { font: inherit; }
a { color: #245ecc; }
</style>
