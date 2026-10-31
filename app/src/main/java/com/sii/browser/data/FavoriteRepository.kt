// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.FavoriteDao
import com.sii.browser.data.db.FavoriteItem
import kotlinx.coroutines.flow.Flow

/**
 * 主页收藏仓库（包装 [FavoriteDao]）。
 */
class FavoriteRepository(private val dao: FavoriteDao) {

    fun all(): Flow<List<FavoriteItem>> = dao.all()

    suspend fun findByUrl(url: String) = dao.findByUrl(url)

    suspend fun add(url: String, title: String, page: Int = 0): FavoriteItem {
        val targetPage = page.coerceIn(0, 4)
        val item = FavoriteItem(
            url = url,
            title = title.ifEmpty { url },
            order = dao.nextOrder(targetPage),
            page = targetPage,
            createdAt = System.currentTimeMillis()
        )
        dao.insert(item)
        return item
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun update(item: FavoriteItem) = dao.update(item)

    /** 将收藏上移/下移一位（只在同一页内交换排序值）。 */
    suspend fun move(item: FavoriteItem, up: Boolean) {
        val list = dao.allOnce().filter { it.page == item.page }
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in list.indices) return
        val other = list[target]
        dao.update(item.copy(order = other.order))
        dao.update(other.copy(order = item.order))
    }

    suspend fun clearAll() = dao.clearAll()

    /** 按给定 id 顺序重排常用网站（只影响这些项所在的页）。 */
    suspend fun reorder(ids: List<Long>) {
        if (ids.isEmpty()) return
        val byId = dao.allOnce().associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        if (ordered.isEmpty()) return
        dao.updateAll(ordered.mapIndexed { index, item -> item.copy(order = index + 1) })
    }

    /** 把一项换到另一页（放在该页末尾）。 */
    suspend fun moveToPage(item: FavoriteItem, page: Int) {
        val target = page.coerceIn(0, 4)
        if (target == item.page) return
        dao.update(item.copy(page = target, order = dao.nextOrder(target)))
    }

    /** 页数减少时，把超出范围的高页项目归到最后一页，避免“网站消失”。 */
    suspend fun clampToPages(pages: Int) {
        val last = (pages - 1).coerceAtLeast(0)
        dao.allOnce().filter { it.page > last }.forEach { item ->
            dao.update(item.copy(page = last, order = dao.nextOrder(last)))
        }
    }
}
