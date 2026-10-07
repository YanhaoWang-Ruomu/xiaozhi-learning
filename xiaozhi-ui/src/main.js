import { createApp } from 'vue'
import App from './App.vue'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'

createApp(App).use(ElButton).use(ElInput).mount('#app')
