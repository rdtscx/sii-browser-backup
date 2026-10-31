// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.FavoriteItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 常用网站管理：按主页页分组，支持上移/下移、移动页面、编辑与删除。
 *
 * 主页分页后，主页上只有拖拽排序，没有跨页入口，这个页面负责补齐。
 */
class HomeSitesManageFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var currentItems: List<FavoriteItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(requireContext().getColor(R.color.bg_page))
        addView(
            PageHeader.build(
                requireContext(),
                getString(R.string.settings_home_sites_manage),
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        addView(
            CardList.scroll(requireContext(), content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        this@HomeSitesManageFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.favoriteRepository.all().collect { items ->
                currentItems = items
                render(items)
            }
        }
    }

    private fun render(items: List<FavoriteItem>) {
        container.removeAllViews()
        container.addView(addRow())
        val pages = AppServices.settings.homeSitePages().coerceIn(1, 5)
        for (page in 0 until pages) {
            val list = items.filter { it.page == page }.sortedBy { it.order }
            container.addView(
                CardList.sectionTitle(
                    requireContext(),
                    getString(R.string.home_page_label, page + 1)
                )
            )
            val card = CardList.card(requireContext())
            if (list.isEmpty()) {
                card.addView(emptyRow())
            } else {
                list.forEachIndexed { index, item ->
                    if (index > 0) card.addView(CardList.divider(requireContext()))
                    card.addView(siteRow(item, page, list, pages))
                }
            }
            container.addView(card)
        }
    }

    private fun addRow(): View = CardList.row(requireContext()).apply {
        addView(TextView(requireContext()).apply {
            text = getString(R.string.home_sites_add)
            setTextSize(14f)
            setTextColor(ThemeAccent.color(requireContext()))
        })
        setOnClickListener { showAddDialog() }
    }

    private fun emptyRow(): View = CardList.row(requireContext()).apply {
        addView(TextView(requireContext()).apply {
            text = getString(R.string.home_sites_page_empty)
            setTextSize(12.5f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        })
    }

    private fun siteRow(
        item: FavoriteItem,
        page: Int,
        pageItems: List<FavoriteItem>,
        pages: Int
    ): View = CardList.row(requireContext()).apply {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = item.title.ifBlank { item.url }
                setTextSize(14.5f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(requireContext()).apply {
                text = item.url
                setTextSize(11.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
        addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(requireContext()).apply {
            text = "›"
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        })
        setOnClickListener { showActions(item, page, pageItems, pages) }
    }

    private fun showActions(
        item: FavoriteItem,
        page: Int,
        pageItems: List<FavoriteItem>,
        pages: Int
    ) {
        val index = pageItems.indexOfFirst { it.id == item.id }
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (index > 0) {
            options.add(getString(R.string.favorite_move_up))
            actions.add {
                io { AppServices.favoriteRepository.move(item, up = true) }
            }
        }
        if (index >= 0 && index < pageItems.lastIndex) {
            options.add(getString(R.string.favorite_move_down))
            actions.add {
                io { AppServices.favoriteRepository.move(item, up = false) }
            }
        }
        if (pages > 1) {
            options.add(getString(R.string.home_page_move))
            actions.add { showMovePage(item, pages) }
        }
        options.add(getString(R.string.home_site_edit))
        actions.add { showEditDialog(item) }
        options.add(getString(R.string.delete))
        actions.add {
            io { AppServices.favoriteRepository.delete(item.id) }
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(item.title.ifBlank { item.url })
            .setItems(options.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .show()
    }

    private fun showMovePage(item: FavoriteItem, pages: Int) {
        val labels = (0 until pages)
            .map { getString(R.string.home_page_label, it + 1) }
            .toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_page_move)
            .setItems(labels) { _, which ->
                io { AppServices.favoriteRepository.moveToPage(item, which) }
            }
            .show()
    }

    private fun showEditDialog(item: FavoriteItem) {
        val nameInput = EditText(requireContext()).apply { setText(item.title) }
        val urlInput = EditText(requireContext()).apply { setText(item.url) }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(urlInput)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_site_edit)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (url.isNotBlank()) {
                    io {
                        AppServices.favoriteRepository.update(
                            item.copy(title = title.ifBlank { url }, url = url)
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val pages = AppServices.settings.homeSitePages().coerceIn(1, 5)
        val nameInput = EditText(requireContext()).apply { hint = getString(R.string.home_title) }
        val urlInput = EditText(requireContext()).apply { hint = getString(R.string.home_url) }
        val page = intArrayOf(0)
        val pageRow = TextView(requireContext()).apply {
            text = getString(R.string.home_page_label, 1)
            setPadding(0, dp(12), 0, dp(4))
            setTextColor(ThemeAccent.color(requireContext()))
            setOnClickListener {
                val labels = (0 until pages)
                    .map { getString(R.string.home_page_label, it + 1) }
                    .toTypedArray()
                android.app.AlertDialog.Builder(requireContext())
                    .setItems(labels) { _, which ->
                        page[0] = which
                        text = labels[which]
                    }
                    .show()
            }
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(urlInput)
            addView(pageRow)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_sites_add)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isNotBlank()) {
                    io {
                        AppServices.favoriteRepository.add(
                            url, nameInput.text.toString().trim(), page[0]
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun io(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { block() }.onFailure {
                Toast.makeText(
                    requireContext(), R.string.settings_home_import_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
