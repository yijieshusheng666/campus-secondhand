/**
 * 商品分类的唯一来源。
 *
 * 原来这份清单在 4 个文件里各写了一遍，已经漂了：管理端写的是「美妆个护」、
 * 其余三处是「美妆护肤」，于是管理端按分类筛选**查不到任何数据**，
 * 而界面上又看不出哪里不对。icons.js 与知识库用的都是「美妆护肤」。
 *
 * 分类名会进数据库（goods.category）与检索索引，改动它等于改数据契约，
 * 所以集中一处后，新增/改名只需要改这个文件，并同步 icons.js 与知识库。
 */

/** 分类名 + 首页胶囊用的 emoji */
export const CATEGORIES = [
  { name: '电子产品', emoji: '📱' },
  { name: '图书教材', emoji: '📚' },
  { name: '生活用品', emoji: '🧺' },
  { name: '运动户外', emoji: '🏀' },
  { name: '二手家具', emoji: '🪑' },
  { name: '美妆护肤', emoji: '💄' }
]

/** 纯分类名数组，给下拉框 / 筛选器用 */
export const CATEGORY_NAMES = CATEGORIES.map(c => c.name)
