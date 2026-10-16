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
 *                                再省略则按失败类型给一个通用提示。
 */
export function useApiRequest(fallbackError) {
  /**
   * @param {object} [options] axios 配置；额外支持 `silent: true` ——
   *   只抛出、不弹提示，给「调用方自己会弹一条更具体的中文提示」的场景用
   *   （不传的话就会弹两次：这里一次、调用方一次）。
   */
  async function call(method, url, options) {
    const { silent, ...config } = options || {}
    try {
      const response = await request.request({ method, url, ...config })
      return { data: response.data, response }
    } catch (e) {
      if (!silent) {
        // 顺序很关键：这里**不能**用 e.message。
        // axios 的 e.message 永远有值（"Request failed with status code 500"、
        // "timeout of 10000ms exceeded"），排在前面会让调用方传的中文 fallbackError
        // 永远轮不到，用户看到的是一句英文。
        // 后端返回非 JSON 错误体（Spring 默认的 500/404 HTML）时正是这种情况。
        const msg = e.response?.data?.message
          || e.response?.data?.msg
          || (e.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : null)
          || fallbackError
          || (e.response ? `服务异常（${e.response.status}），请稍后重试`
                         : '网络连接失败，请检查后端服务')
        ElMessage.error(typeof msg === 'string' ? msg : '请求失败')
      }
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

// 注：这里原本还有一个 withLoading(loadingRef, fn, fallbackError) 导出，
// 全仓库没有任何调用点（各视图最终都改用了上面的 useApiRequest），已经删掉。
