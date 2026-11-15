// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.SiteConfig
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 资源嗅探启用站点：默认所有站点关闭嗅探，只有这里列出的站点会记录资源。
 *
 * 视觉与设置页保持一致：根背景 bg_page、内容边距 14/10/14/20、卡片统一用
 * CardList.pageCard + addRows（跟随后卡片透明度与外框设置）、分组用 sectionTitle、
 * 页尾说明用 aboutText 同款样式。
 */
class SniffSitesFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.bg_page))
        }
        root.addView(
            PageHeader.build(
                requireContext(),
                getString(R.string.sniff_sites_title),
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }
        root.addView(
            CardList.scroll(requireContext(), content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        // 总开关单独一组，与下面的站点列表用分组标题隔开。
        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.settings_sniff)))
        val master = CardList.pageCard(requireContext())
        CardList.addRows(master, listOf(buildMasterSwitch()))
        container.addView(master)

        container.addView(
            CardList.sectionTitle(requireContext(), getString(R.string.sniff_sites_enabled_title))
        )
        val hosts = AppServices.siteConfigRepository.cachedAll()
            .filterValues { SiteConfig.sniffEnabledRaw(it) }
            .keys
            .sorted()
        if (hosts.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.sniff_sites_empty)
                setTextSize(12f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 1.8f)
                setPadding(dp(18), dp(26), dp(18), dp(20))
            })
            return
        }
        val card = CardList.pageCard(requireContext())
        CardList.addRows(card, hosts.map { host -> row(host) })
        container.addView(card)
    }

    /** 资源嗅探总开关：与设置页的 switchRow 同一套字号/副说明样式。 */
    private fun buildMasterSwitch(): View {
        val context = requireContext()
        val switch = android.widget.Switch(context).apply {
            isChecked = AppServices.settings.sniffEnabled()
            trackDrawable = androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.switch_track)
            thumbDrawable = androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.switch_thumb)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
            setOnCheckedChangeListener { _, value -> AppServices.settings.setSniffEnabled(value) }
        }
        return CardList.row(context).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = context.getString(R.string.settings_sniff)
                        setTextSize(14f)
                        setTextColor(context.getColor(R.color.text_primary))
                    })
                    addView(TextView(context).apply {
                        text = context.getString(R.string.settings_sniff_desc)
                        setTextSize(12.5f)
                        setTextColor(context.getColor(R.color.text_secondary))
                        alpha = 0.55f
                        setLineSpacing(0f, 1.35f)
                        setPadding(0, dp(3), 0, 0)
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(switch)
        }
    }

    private fun row(host: String): View {
        val name = TextView(requireContext()).apply {
            text = host
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val remove = TextView(requireContext()).apply {
            text = getString(R.string.sniff_sites_remove)
            setTextSize(13f)
            setTextColor(ThemeAccent.color(requireContext()))
            setPadding(dp(12), dp(6), dp(2), dp(6))
            setOnClickListener { removeHost(host) }
        }
        return CardList.row(requireContext()).apply {
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove)
        }
    }

    private fun removeHost(host: String) {
        val text = SiteConfig.withSniffEnabled(
            AppServices.siteConfigRepository.cachedConfig(host), false
        )
        AppServices.siteConfigRepository.cachePut(host, text)
        lifecycleScope.launch(Dispatchers.IO) {
            AppServices.siteConfigRepository.put(host, text)
        }
        Toast.makeText(requireContext(), R.string.sniff_sites_removed, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}