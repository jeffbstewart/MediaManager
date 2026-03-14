package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.ShortcutEventListener
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.AuthService
import org.slf4j.LoggerFactory

/**
 * Live TV viewer — streams OTA channels via HLS.
 * Accessible to all authenticated users (viewer + admin), gated by content rating.
 * Admin users can rate channel reception quality inline.
 */
@Route(value = "live-tv", layout = MainLayout::class)
@PageTitle("Live TV")
class LiveTvView : KComposite(), HasUrlParameter<String> {

    private val log = LoggerFactory.getLogger(LiveTvView::class.java)

    private lateinit var videoContainer: Div
    private lateinit var channelLabel: Span
    private lateinit var qualityContainer: Div
    private lateinit var statusLabel: Span

    private var channels: List<LiveTvChannel> = emptyList()
    private var currentIndex: Int = -1
    private var currentUser: AppUser? = null

    private val root = ui {
        verticalLayout {
            setSizeFull()
            isPadding = false
            isSpacing = false

            currentUser = AuthService.getCurrentUser()

            // Load channels filtered by user's quality preference
            val allChannels = LiveTvChannel.findAll()
                .filter { it.enabled }
                .filter { ch ->
                    val tuner = LiveTvTuner.findById(ch.tuner_id)
                    tuner != null && tuner.enabled
                }
                .sortedWith(compareBy({ it.display_order }, { it.guide_number.toDoubleOrNull() ?: 9999.0 }))

            val minQuality = currentUser?.live_tv_min_quality ?: 4
            channels = allChannels.filter { it.reception_quality >= minQuality }

            if (channels.isEmpty()) {
                add(Div().apply {
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("height", "100%")
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("padding", "var(--lumo-space-xl)")
                    if (allChannels.isEmpty()) {
                        add(Span("No channels available. Ask an admin to configure a tuner."))
                    } else {
                        add(Span("No channels meet your quality filter (min $minQuality stars). Lower your threshold in Profile or ask an admin to rate channels."))
                    }
                })
                return@verticalLayout
            }

            // Top control bar
            val controlBar = HorizontalLayout().apply {
                width = "100%"
                isPadding = true
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                style.set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                style.set("background", "var(--lumo-base-color)")
                style.set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                style.set("flex-shrink", "0")
            }

            // Previous channel
            val prevBtn = Button(VaadinIcon.ANGLE_LEFT.create()) {
                stepChannel(-1)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                element.setAttribute("title", "Previous channel")
            }

            // Channel label (guide number + name)
            channelLabel = Span().apply {
                style.set("font-weight", "600")
                style.set("font-size", "var(--lumo-font-size-l)")
                style.set("white-space", "nowrap")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("min-width", "0")
            }

            // Next channel
            val nextBtn = Button(VaadinIcon.ANGLE_RIGHT.create()) {
                stepChannel(1)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                element.setAttribute("title", "Next channel")
            }

            // Channel picker ComboBox
            val channelPicker = ComboBox<LiveTvChannel>().apply {
                placeholder = "Jump to channel..."
                isClearButtonVisible = true
                width = "220px"
                style.set("flex-shrink", "0")
                setItemLabelGenerator { "${it.guide_number} ${it.guide_name}" }
                setItems(channels)
                addValueChangeListener { event ->
                    val selected = event.value ?: return@addValueChangeListener
                    val idx = channels.indexOf(selected)
                    if (idx >= 0) {
                        tuneToChannel(idx)
                    }
                    value = null // reset so same channel can be re-selected
                }
            }

            // Status indicator
            statusLabel = Span().apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("flex-shrink", "0")
            }

            // Quality rating (admin only)
            qualityContainer = Div().apply {
                style.set("display", "flex")
                style.set("align-items", "center")
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-shrink", "0")
                isVisible = currentUser?.isAdmin() == true
            }

            val spacer = Span().apply { style.set("flex-grow", "1") }

            controlBar.add(prevBtn, channelLabel, nextBtn, spacer, channelPicker, qualityContainer, statusLabel)
            add(controlBar)

            // Video container (fills remaining space)
            videoContainer = Div().apply {
                width = "100%"
                style.set("flex-grow", "1")
                style.set("background", "black")
                style.set("display", "flex")
                style.set("align-items", "center")
                style.set("justify-content", "center")
                style.set("min-height", "0")
                style.set("position", "relative")
            }
            add(videoContainer)

        }
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter guideNumber: String?) {
        if (channels.isEmpty()) return
        val startIndex = if (guideNumber != null) {
            channels.indexOfFirst { it.guide_number == guideNumber }.takeIf { it >= 0 } ?: 0
        } else 0
        tuneToChannel(startIndex)
    }

    private fun tuneToChannel(index: Int) {
        if (index < 0 || index >= channels.size) return
        currentIndex = index
        val channel = channels[index]

        // Update channel label
        channelLabel.text = "${channel.guide_number} ${channel.guide_name}"

        // Update URL to persist channel across reloads (replaceState, no navigation)
        UI.getCurrent()?.page?.history?.replaceState(null, "live-tv/${channel.guide_number}")

        // Update quality rating UI
        updateQualityUI(channel)

        // Update status
        statusLabel.text = "Tuning..."

        // Show loading spinner overlay while stream starts
        val streamUrl = "/live-tv-stream/${channel.id}/stream.m3u8"
        videoContainer.element.setProperty("innerHTML", """
            <div id="live-tv-loading" style="display:flex;flex-direction:column;align-items:center;justify-content:center;
                 width:100%;height:100%;color:#999;font-size:1.1em;gap:16px;">
                <div style="width:48px;height:48px;border:4px solid #333;border-top-color:#999;border-radius:50%;
                     animation:live-tv-spin 1s linear infinite;"></div>
                <div>Tuning to ${channel.guide_number} ${channel.guide_name}...</div>
            </div>
            <style>@keyframes live-tv-spin { to { transform: rotate(360deg); } }</style>
            <video id="live-tv-player" autoplay playsinline
                   style="width:100%;height:100%;object-fit:contain;background:black;display:none;">
            </video>
        """.trimIndent())

        // Load HLS.js for Chrome/Firefox, native HLS for Safari
        videoContainer.element.executeJs("""
            var video = document.getElementById('live-tv-player');
            var loadingEl = document.getElementById('live-tv-loading');
            if (!video) return;
            var statusEl = $0;
            var url = $1;

            function showVideo() {
                if (loadingEl) loadingEl.style.display = 'none';
                video.style.display = '';
            }

            function showError(msg) {
                if (loadingEl) {
                    loadingEl.innerHTML = '<div style="font-size:2em;margin-bottom:8px;">&#x1f4e1;</div>' +
                        '<div>' + msg + '</div>' +
                        '<div style="font-size:0.85em;color:#666;margin-top:8px;">Try another channel with the arrow keys</div>';
                }
            }

            video.addEventListener('playing', function() { showVideo(); statusEl.textContent = 'Playing'; });
            video.addEventListener('waiting', function() { statusEl.textContent = 'Buffering...'; });
            video.addEventListener('stalled', function() { statusEl.textContent = 'Stalled...'; });

            // Destroy previous HLS instance if any
            if (window._liveTvHls) {
                window._liveTvHls.destroy();
                window._liveTvHls = null;
            }

            function startNative() {
                video.src = url;
                video.addEventListener('error', function() {
                    statusEl.textContent = 'Error';
                    showError('Channel unavailable');
                });
                video.play().catch(function(e) { console.log('Live TV autoplay:', e.message); });
            }

            function startWithHlsJs() {
                if (typeof Hls === 'undefined') {
                    var s = document.createElement('script');
                    s.src = '/hls.min.js';
                    s.onload = function() { startWithHlsJs(); };
                    s.onerror = function() {
                        statusEl.textContent = 'Cannot load HLS player';
                        showError('Cannot load HLS player');
                    };
                    document.head.appendChild(s);
                    return;
                }
                var hls = new Hls({ enableWorker: true, lowLatencyMode: true });
                window._liveTvHls = hls;
                hls.loadSource(url);
                hls.attachMedia(video);
                hls.on(Hls.Events.MANIFEST_PARSED, function() {
                    video.play().catch(function(e) { console.log('Live TV autoplay:', e.message); });
                });
                hls.on(Hls.Events.ERROR, function(event, data) {
                    if (data.fatal) {
                        console.error('HLS fatal error:', data.type, data.details);
                        if (data.response && data.response.code === 503) {
                            statusEl.textContent = 'Tuners busy';
                            showError('All tuners are busy');
                        } else if (data.response && (data.response.code === 502 || data.response.code === 504)) {
                            statusEl.textContent = 'No signal';
                            showError('No signal on this channel');
                        } else if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                            statusEl.textContent = 'Channel unavailable';
                            showError('Channel unavailable');
                        } else {
                            statusEl.textContent = 'Stream error';
                            showError('Stream error');
                        }
                    }
                });
            }

            var nativeHls = video.canPlayType('application/vnd.apple.mpegurl');
            var hlsJsLoaded = typeof Hls !== 'undefined';
            var hlsJsSupported = hlsJsLoaded && Hls.isSupported();
            console.log('[LiveTV] canPlayType mpegurl=' + JSON.stringify(nativeHls) +
                        ', Hls loaded=' + hlsJsLoaded +
                        ', Hls.isSupported=' + hlsJsSupported +
                        ', UA=' + navigator.userAgent);

            // Always use HLS.js (loads on demand if needed). Chrome 145+ returns
            // "maybe" for canPlayType('application/vnd.apple.mpegurl') but can't
            // actually play HLS natively. Only fall back to native HLS on browsers
            // where HLS.js explicitly reports it's not supported (e.g. old iOS Safari).
            if (hlsJsLoaded && !hlsJsSupported) {
                startNative();
            } else {
                startWithHlsJs();
            }
        """, statusLabel.element, streamUrl)
    }

    private fun stepChannel(delta: Int) {
        if (channels.isEmpty()) return
        val newIndex = (currentIndex + delta + channels.size) % channels.size
        tuneToChannel(newIndex)
    }

    private fun updateQualityUI(channel: LiveTvChannel) {
        qualityContainer.removeAll()
        if (currentUser?.isAdmin() != true) return

        qualityContainer.add(Span("Quality:").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        })

        for (star in 1..5) {
            val filled = star <= channel.reception_quality
            val starBtn = Button(
                if (filled) VaadinIcon.STAR.create() else VaadinIcon.STAR_O.create()
            ) {
                // Update channel quality in DB
                channel.reception_quality = star
                channel.save()
                updateQualityUI(channel) // refresh stars
                Notification.show("Quality set to $star for ${channel.guide_name}", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL)
                style.set("min-width", "unset")
                style.set("padding", "0")
                style.set("color", if (filled) "var(--lumo-primary-color)" else "var(--lumo-contrast-50pct)")
                element.setAttribute("title", "$star star${if (star > 1) "s" else ""}")
            }
            qualityContainer.add(starBtn)
        }
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        // Register keyboard shortcuts for channel stepping
        attachEvent.ui.addShortcutListener(ShortcutEventListener { stepChannel(1) }, Key.ARROW_RIGHT)
        attachEvent.ui.addShortcutListener(ShortcutEventListener { stepChannel(-1) }, Key.ARROW_LEFT)
    }
}
