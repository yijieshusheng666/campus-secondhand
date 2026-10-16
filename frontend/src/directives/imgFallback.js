/**
 * v-img-fallback：图片加载失败时自动换成占位图。
 *
 * <p>为什么要它：商品图的 URL 存在数据库里，只要那条记录指向的文件丢了
 * （上传目录被清掉、从别的目录启动导致存到别处、手工删库），页面就会显示
 * 浏览器自带的裂图图标，同时控制台刷一条红色 404。之前项目里几十个 &lt;img&gt;
 * 全都没有 @error，这个破图会一路展示到用户面前。</p>
 *
 * <p>用指令而不是给每个 img 写 @error，是为了避免「漏加一个就多一个破图」。
 * 用法：<code>&lt;img v-img-fallback :src="url"&gt;</code></p>
 */

// 内联 SVG 占位图：走 data URI，不产生额外网络请求，也就不会二次报错。
const PLACEHOLDER =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120" viewBox="0 0 120 120">' +
      '<rect width="120" height="120" fill="#f4f5f7"/>' +
      '<path d="M30 78l20-24 14 16 10-11 16 19z" fill="#c9ced6"/>' +
      '<circle cx="44" cy="42" r="7" fill="#c9ced6"/>' +
      '<text x="60" y="104" font-size="11" fill="#a8b1c2" text-anchor="middle" ' +
      'font-family="sans-serif">图片缺失</text>' +
    '</svg>'
  )

export const imgFallback = {
  mounted(el, binding) {
    el.addEventListener('error', handler, { once: true })
    function handler() {
      // 用 binding.arg 支持 <img v-img-fallback:customUrl> 传自定义占位图
      el.src = binding.arg || PLACEHOLDER
      el.classList.add('img-fallback')
    }
  }
}

export default imgFallback
export { PLACEHOLDER as IMG_PLACEHOLDER }
