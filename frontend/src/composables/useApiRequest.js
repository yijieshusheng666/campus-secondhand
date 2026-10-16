/**
 * 统一封装后端请求的三件套：loading 状态 + 错误提示 + 返回 data。
 *
 * <p>之前每个视图都自己写：
 * <pre>
 * loading.value = true
 * try {
 *   const { data } = await request.get('/xxx')
 *   // 用 data.data
 * } catch (e) {
 *   ElMessage.error('xxx 失败，请检查后端服务')
 * } finally {
 *   loading.value = false
 * }
 * </pre>
 * 这种 try/catch 模板在 13 个视图里出现了 ~20 次，且错误提示口径不统一。
 * 抽到这一处之后：默认错误提示跟后端 message 对齐，loading 状态自动收尾，
 * 调用方只关心成功路径。</p>
 */
import { ElMessage } from 'element-plus'
import request from '../api'

/**
 * 包一层 axios，返回值统一是 `{ data, response }`。
 * - `data`：后端响应的 JSON 体
 * - `response`：原始 axios 响应，方便需要 status / headers 的场景
 *
 * @param {string} [fallbackError] 自定义错误提示；省略则使用后端的 message，
 *                                再省略则给一个通用提示。
 */
export function useApiRequest(fallbackError) {
  async function call(method, url, options) {
    try {
      const response = await request.request({
        method,
        url,
        ...options
      })
      return { data: response.data, response }
    } catch (e) {
      const msg = e.response?.data?.message
        || e.response?.data?.msg
        || e.message
        || fallbackError
        || '请求失败，请检查后端服务'
      ElMessage.error(typeof msg === 'string' ? msg : '请求失败')
      throw e
    }
  }

  return {
    get: (url, options) => call('GET', url, options),
    post: (url, data, options) => call('POST', url, { ...options, data }),
    put: (url, data, options) => call('PUT', url, { ...options, data }),
    delete: (url, options) => call('DELETE', url, options)
  }
}

/**
 * 简单的 loading + 错误处理包裹：保留组件里手写的成功逻辑，
 * 只把 try/catch + loading 收尾挪到这里。
 *
 * 用法：
 * <pre>
 * const loading = ref(false)
 * await withLoading(loading, async () => {
 *   const { data } = await request.get('/orders')
 *   orders.value = data
 * })
 * </pre>
 */
export async function withLoading(loadingRef, fn, fallbackError) {
  loadingRef.value = true
  try {
    return await fn()
  } catch (e) {
    const msg = e.response?.data?.message
      || e.response?.data?.msg
      || (typeof fallbackError === 'string' ? fallbackError : null)
      || '请求失败，请检查后端服务'
    ElMessage.error(typeof msg === 'string' ? msg : '请求失败')
    throw e
  } finally {
    loadingRef.value = false
  }
}
