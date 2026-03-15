package net.stewart.mediamanager

import com.vaadin.flow.component.ClientCallable
import com.vaadin.flow.component.Html
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * Dialog that plays a video in the browser using HTML5 <video> element.
 * MP4/M4V files stream directly; MKV/AVI files are served from the pre-transcoded
 * ForBrowser mirror. The play button in the view is only shown when the file is ready.
 *
 * Features:
 * - Resume prompt: if saved progress exists, offers Resume / Start Over
 * - Periodic position reporting (~60s) via fetch to /playback-progress/{id}
 * - Reports position on pause and on dialog close (via sendBeacon)
 * - Auto-clears progress when video reaches near-end (server-side)
 * - Auto-play next episode: "Up Next" overlay 2 minutes before end with 10s countdown
 */
class VideoPlayerDialog(
    private var transcodeId: Long,
    private val titleName: String,
    private var fileName: String?,
    private val subtitlesEnabled: Boolean = true
) : Dialog() {

    private val contentArea = VerticalLayout().apply {
        isPadding = false
        isSpacing = false
        setSizeFull()
    }

    private var nextEpisode: NextEpisodeInfo? = null

    data class NextEpisodeInfo(val transcodeId: Long, val label: String)

    init {
        headerTitle = fileName?.let { "$titleName — $it" } ?: titleName
        width = "80vw"
        height = "80vh"
        isResizable = true
        isDraggable = true

        // Close button in the header — always visible, even on small screens
        val closeBtn = Button(VaadinIcon.CLOSE_SMALL.create()) { close() }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            element.setAttribute("title", "Close")
        }
        header.add(closeBtn)

        add(contentArea)

        // On mobile, go fullscreen so the video + header fit without scrolling
        element.executeJs(
            "if(!document.getElementById('vpd-responsive')){" +
            "var s=document.createElement('style');s.id='vpd-responsive';" +
            "s.textContent='@media(max-width:600px){" +
            "vaadin-dialog-overlay [part=overlay]{" +
            "width:100vw!important;height:100vh!important;" +
            "max-width:100vw!important;max-height:100vh!important;" +
            "top:0!important;left:0!important;" +
            "border-radius:0!important}" +
            "}';" +
            "document.head.appendChild(s)}"
        )

        // Report final position when dialog closes (sendBeacon is reliable during navigation)
        addOpenedChangeListener { event ->
            if (!event.isOpened) {
                element.executeJs(
                    "var v=document.getElementById('vpd-video');" +
                    "if(v&&v.currentTime>0){" +
                    "var data=JSON.stringify({position:v.currentTime,duration:v.duration||0});" +
                    "navigator.sendBeacon('/playback-progress/'+v.__transcodeId,new Blob([data],{type:'application/json'}));}"
                )
            }
        }

        nextEpisode = findNextPlayableEpisode(transcodeId)

        // Check for saved progress — show resume prompt if position > 10s
        val savedProgress = PlaybackProgressService.getProgress(transcodeId)
        if (savedProgress != null && savedProgress.position_seconds > 10) {
            showResumePrompt(savedProgress.position_seconds)
        } else {
            showVideoPlayer(seekTo = null)
        }
    }

    /**
     * Called from client-side JS when the "Up Next" countdown expires or user clicks "Play Now".
     */
    @ClientCallable
    fun playNextEpisode() {
        val next = nextEpisode ?: return
        transcodeId = next.transcodeId
        fileName = next.label
        headerTitle = "$titleName — ${next.label}"
        nextEpisode = findNextPlayableEpisode(next.transcodeId)

        val savedProgress = PlaybackProgressService.getProgress(transcodeId)
        if (savedProgress != null && savedProgress.position_seconds > 10) {
            showResumePrompt(savedProgress.position_seconds)
        } else {
            showVideoPlayer(seekTo = null)
        }
    }

    /**
     * Shows a resume prompt with Resume and Start Over buttons.
     */
    private fun showResumePrompt(savedPosition: Double) {
        contentArea.removeAll()

        val mins = (savedPosition / 60).toInt()
        val secs = (savedPosition % 60).toInt()
        val formatted = "%d:%02d".format(mins, secs)

        val promptLayout = VerticalLayout().apply {
            setSizeFull()
            justifyContentMode = FlexComponent.JustifyContentMode.CENTER
            defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
            style.set("background", "#000")

            add(Span("Resume from $formatted?").apply {
                style.set("color", "#fff")
                style.set("font-size", "var(--lumo-font-size-xl)")
            })

            val buttonRow = HorizontalLayout().apply {
                isSpacing = true

                add(Button("Resume").apply {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener { showVideoPlayer(seekTo = savedPosition) }
                })
                add(Button("Start Over").apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                    addClickListener {
                        PlaybackProgressService.clearProgress(transcodeId)
                        showVideoPlayer(seekTo = null)
                    }
                })
            }
            add(buttonRow)
        }

        contentArea.add(promptLayout)
    }

    /**
     * Shows the HTML5 video player pointing at /stream/{transcodeId}.
     * Includes a loading/buffering overlay that responds to video element events.
     * If seekTo is provided, the video will seek to that position once playable.
     * If a next episode is available, shows an "Up Next" overlay near the end.
     */
    private fun showVideoPlayer(seekTo: Double?) {
        contentArea.removeAll()

        val streamUrl = "/stream/$transcodeId"

        // Build "Up Next" overlay HTML (only included if next episode exists)
        val upNextOverlay = if (nextEpisode != null) {
            val escapedLabel = nextEpisode!!.label
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
            "<div id=\"vpd-upnext\" style=\"display:none;position:absolute;bottom:80px;right:20px;" +
            "background:rgba(0,0,0,0.92);border-radius:8px;padding:16px 20px;z-index:20;" +
            "color:white;min-width:250px;border:1px solid rgba(255,255,255,0.15);" +
            "box-shadow:0 4px 24px rgba(0,0,0,0.5);\">" +
            "<div style=\"font-size:11px;color:rgba(255,255,255,0.5);text-transform:uppercase;" +
            "letter-spacing:1px;margin-bottom:4px;\">Up Next</div>" +
            "<div style=\"font-size:14px;font-weight:500;margin-bottom:12px;\">$escapedLabel</div>" +
            "<div style=\"display:flex;align-items:center;gap:12px;\">" +
            "<button id=\"vpd-upnext-play\" style=\"background:#1976D2;color:white;border:none;" +
            "padding:6px 16px;border-radius:4px;cursor:pointer;font-size:13px;" +
            "font-weight:500;\">Play Now</button>" +
            "<span id=\"vpd-upnext-countdown\" style=\"font-size:12px;" +
            "color:rgba(255,255,255,0.5);\"></span>" +
            "<button id=\"vpd-upnext-cancel\" style=\"background:none;border:none;" +
            "color:rgba(255,255,255,0.5);cursor:pointer;font-size:12px;" +
            "margin-left:auto;text-decoration:underline;\">Cancel</button>" +
            "</div></div>"
        } else ""

        val videoHtml = """
            <div style="width:100%; height:100%; position:relative; background:#000;">
                <video id="vpd-video" controls autoplay playsinline
                       style="max-width:100%; max-height:100%; width:100%; height:100%;"
                       src="$streamUrl">
                    Your browser does not support the video tag.
                </video>
                <div id="vpd-overlay" style="position:absolute; top:0; left:0; width:100%; height:100%;
                     display:flex; flex-direction:column; align-items:center; justify-content:center;
                     background:rgba(0,0,0,0.85); z-index:10; transition:opacity 0.3s;">
                    <div id="vpd-spinner" style="width:48px; height:48px; border:4px solid rgba(255,255,255,0.2);
                         border-top-color:#fff; border-radius:50%;
                         animation:vpd-spin 1s linear infinite;"></div>
                    <div id="vpd-status" style="color:#fff; margin-top:16px; font-size:14px;">
                        Starting playback…</div>
                </div>
                <div id="vpd-thumb-preview" style="display:none;position:absolute;bottom:60px;
                     background:#000;border:2px solid rgba(255,255,255,0.3);border-radius:4px;
                     width:160px;height:90px;overflow:hidden;pointer-events:none;z-index:15;
                     box-shadow:0 2px 8px rgba(0,0,0,0.6);"></div>
                <div id="vpd-chapter-bar" style="display:none;position:absolute;bottom:20px;left:0;right:0;
                     height:4px;z-index:16;pointer-events:none;"></div>
                <button id="vpd-skip-intro" style="display:none;position:absolute;bottom:80px;right:20px;
                     background:rgba(0,0,0,0.85);color:white;border:1px solid rgba(255,255,255,0.4);
                     padding:10px 24px;border-radius:4px;cursor:pointer;font-size:14px;font-weight:500;
                     z-index:18;transition:background 0.2s;"
                     onmouseover="this.style.background='rgba(255,255,255,0.2)'"
                     onmouseout="this.style.background='rgba(0,0,0,0.85)'">Skip Intro</button>
                $upNextOverlay
                <style>@keyframes vpd-spin{to{transform:rotate(360deg)}}
                .vpd-ch-tick{position:absolute;top:0;width:2px;height:4px;background:rgba(255,220,0,0.9);
                pointer-events:none;}
                .vpd-chromaprint-region{position:absolute;top:0;height:100%;
                background:rgba(0,220,255,0.6);pointer-events:none;
                border-left:1px solid rgba(0,180,255,0.8);border-right:1px solid rgba(0,180,255,0.8);}</style>
            </div>
        """.trimIndent()

        val htmlComponent = Html(videoHtml)
        contentArea.add(htmlComponent)

        // Build the seek JS fragment (empty string if no seek needed)
        val seekJs = if (seekTo != null) {
            "var seekDone=false;" +
            "v.addEventListener('canplay',function(){if(!seekDone){seekDone=true;v.currentTime=$seekTo;}});"
        } else ""

        // Build "Up Next" JS — countdown overlay + auto-advance via @ClientCallable
        // The time-based trigger (120s before end) is removed; chapter data drives the trigger.
        // Fallback: 'ended' event fires if no chapter data is available.
        val upNextJs = if (nextEpisode != null) {
            "var upnext=document.getElementById('vpd-upnext');" +
            "if(upnext){" +
            "var upDismissed=false,upShown=false,upTimer=null,upSecs=10;" +
            "var cntEl=document.getElementById('vpd-upnext-countdown');" +
            "var dialogEl=\$0;" +
            "function startCountdown(){" +
            "if(upDismissed||upShown)return;" +
            "upShown=true;upSecs=10;upnext.style.display='block';" +
            "cntEl.textContent='Playing in 10s';" +
            "upTimer=setInterval(function(){upSecs--;" +
            "if(upSecs<=0){doAdvance();}else{cntEl.textContent='Playing in '+upSecs+'s';}},1000);}" +
            "function doAdvance(){" +
            "if(upTimer)clearInterval(upTimer);" +
            "if(v.currentTime>0){navigator.sendBeacon('/playback-progress/'+tid," +
            "new Blob([JSON.stringify({position:v.currentTime,duration:v.duration||0})]," +
            "{type:'application/json'}));}" +
            "dialogEl.\$server.playNextEpisode();}" +
            "document.getElementById('vpd-upnext-play').onclick=doAdvance;" +
            "document.getElementById('vpd-upnext-cancel').onclick=function(){" +
            "upDismissed=true;if(upTimer)clearInterval(upTimer);upnext.style.display='none';};" +
            "v.addEventListener('ended',function(){startCountdown();});}"
        } else ""

        // Wire up video event listeners + progress tracking via executeJs
        contentArea.element.executeJs(
            "var v=document.getElementById('vpd-video');" +
            "var o=document.getElementById('vpd-overlay');" +
            "var s=document.getElementById('vpd-status');" +
            "if(!v||!o||!s) return;" +
            "function show(msg){o.style.opacity='1';o.style.pointerEvents='auto';s.textContent=msg;}" +
            "function hide(){o.style.opacity='0';o.style.pointerEvents='none';}" +
            "v.addEventListener('loadstart',function(){show('Starting playback\\u2026');});" +
            "v.addEventListener('waiting',function(){show('Buffering\\u2026');});" +
            "v.addEventListener('canplay',function(){hide();});" +
            "v.addEventListener('playing',function(){hide();});" +
            "v.addEventListener('error',function(){show('Playback error');});" +
            // Seek to saved position if resuming
            seekJs +
            // Check for subtitles and add track only if available
            "fetch('$streamUrl/subs.vtt',{method:'HEAD'}).then(function(r){" +
            "if(r.ok){var t=document.createElement('track');" +
            "t.kind='subtitles';t.src='$streamUrl/subs.vtt';t.srclang='en';t.label='English';" +
            "${if (subtitlesEnabled) "t.default=true;" else ""}" +
            "v.appendChild(t);}}).catch(function(){});" +
            // Progress reporting: periodic (~60s) + on pause
            "var tid=$transcodeId;" +
            "var lastReport=0;" +
            "function reportProgress(){" +
            "if(v.currentTime>0){" +
            "fetch('/playback-progress/'+tid,{method:'POST'," +
            "headers:{'Content-Type':'application/json'}," +
            "body:JSON.stringify({position:v.currentTime,duration:v.duration||0})});}}" +
            "v.addEventListener('timeupdate',function(){" +
            "var now=Date.now();" +
            "if(now-lastReport<60000)return;" +
            "lastReport=now;" +
            "reportProgress();});" +
            "v.addEventListener('pause',function(){reportProgress();});" +
            // Store transcodeId on video element for close handler
            "v.__transcodeId=tid;" +
            // "Up Next" auto-play logic
            upNextJs +
            // Thumbnail preview on seek bar hover
            "var thumbPreview=document.getElementById('vpd-thumb-preview');" +
            "var vttCues=null;" +
            "fetch('/stream/'+tid+'/thumbs.vtt').then(function(r){" +
            "if(!r.ok)return null;return r.text();}).then(function(txt){" +
            "if(!txt)return;" +
            "vttCues=[];" +
            "var lines=txt.split('\\n');var i=0;" +
            "while(i<lines.length){" +
            "var line=lines[i].trim();" +
            "var m=line.match(/^(\\d+:\\d+:\\d+\\.\\d+)\\s*-->\\s*(\\d+:\\d+:\\d+\\.\\d+)/);" +
            "if(m){" +
            "var startT=m[1].split(':');var startS=parseInt(startT[0])*3600+parseInt(startT[1])*60+parseFloat(startT[2]);" +
            "var endT=m[2].split(':');var endS=parseInt(endT[0])*3600+parseInt(endT[1])*60+parseFloat(endT[2]);" +
            "i++;if(i<lines.length){" +
            "var info=lines[i].trim();" +
            "var xywh=info.match(/#xywh=(\\d+),(\\d+),(\\d+),(\\d+)/);" +
            "var sheetMatch=info.match(/thumbs_(\\d+)\\.jpg/);" +
            "if(xywh&&sheetMatch){" +
            "vttCues.push({start:startS,end:endS,sheet:parseInt(sheetMatch[1])," +
            "x:parseInt(xywh[1]),y:parseInt(xywh[2]),w:parseInt(xywh[3]),h:parseInt(xywh[4])});" +
            "}}}i++;}}).catch(function(){});" +
            // Attach hover listener to the video container
            "var container=v.parentElement;" +
            "container.addEventListener('mousemove',function(e){" +
            "if(!vttCues||!v.duration||v.duration<=0)return;" +
            "var rect=v.getBoundingClientRect();" +
            "var bottomZone=rect.bottom-40;" +
            "if(e.clientY<bottomZone-20||e.clientY>rect.bottom){thumbPreview.style.display='none';return;}" +
            "var frac=(e.clientX-rect.left)/rect.width;" +
            "if(frac<0||frac>1){thumbPreview.style.display='none';return;}" +
            "var time=frac*v.duration;" +
            "var cue=null;for(var c=0;c<vttCues.length;c++){" +
            "if(time>=vttCues[c].start&&time<vttCues[c].end){cue=vttCues[c];break;}}" +
            "if(!cue){thumbPreview.style.display='none';return;}" +
            "var imgUrl='/stream/'+tid+'/thumbs_'+cue.sheet+'.jpg';" +
            "thumbPreview.style.display='block';" +
            "thumbPreview.style.width=cue.w+'px';thumbPreview.style.height=cue.h+'px';" +
            "thumbPreview.style.backgroundImage='url('+imgUrl+')';" +
            "thumbPreview.style.backgroundPosition='-'+cue.x+'px -'+cue.y+'px';" +
            "var left=e.clientX-rect.left-cue.w/2;" +
            "left=Math.max(0,Math.min(left,rect.width-cue.w));" +
            "thumbPreview.style.left=left+'px';" +
            "});" +
            "container.addEventListener('mouseleave',function(){" +
            "if(thumbPreview)thumbPreview.style.display='none';});" +
            // Chapter markers + skip segments
            "var chBar=document.getElementById('vpd-chapter-bar');" +
            "var skipBtn=document.getElementById('vpd-skip-intro');" +
            // Sync chapter bar visibility with native controls (fade with mouse idle)
            "var chBarHideTimer=null;var chBarHasMarkers=false;" +
            "function showChBar(){if(!chBarHasMarkers)return;chBar.style.opacity='1';chBar.style.transition='opacity 0.3s';}" +
            "function hideChBar(){if(!chBarHasMarkers)return;chBar.style.opacity='0';chBar.style.transition='opacity 0.3s';}" +
            "container.addEventListener('mousemove',function(){showChBar();" +
            "if(chBarHideTimer)clearTimeout(chBarHideTimer);" +
            "chBarHideTimer=setTimeout(hideChBar,3000);});" +
            "container.addEventListener('mouseleave',function(){hideChBar();});" +
            "v.addEventListener('pause',function(){showChBar();if(chBarHideTimer)clearTimeout(chBarHideTimer);});" +
            "v.addEventListener('play',function(){if(chBarHideTimer)clearTimeout(chBarHideTimer);chBarHideTimer=setTimeout(hideChBar,3000);});" +
            "var chapData=null,skipData=null;" +
            "fetch('/stream/'+tid+'/chapters.json').then(function(r){" +
            "if(!r.ok)return null;return r.json();}).then(function(d){" +
            "if(!d)return;" +
            "chapData=d.chapters||[];skipData=d.skipSegments||[];" +
            "if(chapData.length>0){" +
            // Wait for duration to be known before rendering markers
            "function renderMarkers(){" +
            "if(!v.duration||v.duration<=0)return;" +
            "chBar.style.display='block';chBar.style.opacity='0';chBarHasMarkers=true;" +
            "chBar.innerHTML='';" +
            "for(var i=0;i<chapData.length;i++){" +
            "var ch=chapData[i];" +
            "var pct=(ch.start/v.duration)*100;" +
            "if(pct<0||pct>100)continue;" +
            "var tick=document.createElement('div');" +
            "tick.className='vpd-ch-tick';" +
            "tick.style.left=pct+'%';" +
            "tick.dataset.idx=i;" +
            "tick.dataset.start=ch.start;" +
            "chBar.appendChild(tick);}}" +
            "if(v.duration>0){renderMarkers();}else{" +
            "v.addEventListener('loadedmetadata',renderMarkers);}}" +
            // Cyan overlay regions for externally-detected skip segments
            "if(skipData){" +
            "function renderSkipRegions(){" +
            "if(!v.duration||v.duration<=0)return;" +
            "for(var si=0;si<skipData.length;si++){" +
            "var seg=skipData[si];" +
            "if(seg.method&&seg.method!=='CHAPTER'){" +
            "chBar.style.display='block';chBarHasMarkers=true;" +
            "var leftPct=(seg.start/v.duration)*100;" +
            "var widthPct=((seg.end-seg.start)/v.duration)*100;" +
            "var region=document.createElement('div');" +
            "region.className='vpd-chromaprint-region';" +
            "region.style.left=leftPct+'%';" +
            "region.style.width=widthPct+'%';" +
            "chBar.appendChild(region);}}}" +
            "if(v.duration>0){renderSkipRegions();}else{" +
            "v.addEventListener('loadedmetadata',renderSkipRegions);}}" +
            // Skip Intro button logic
            "if(skipData&&skipData.length>0){" +
            "var introSeg=null;" +
            "for(var si=0;si<skipData.length;si++){" +
            "if(skipData[si].type==='INTRO')introSeg=skipData[si];}" +
            "if(introSeg){" +
            "skipBtn.onclick=function(){v.currentTime=introSeg.end;};" +
            "v.addEventListener('timeupdate',function(){" +
            "if(introSeg&&v.currentTime>=introSeg.start&&v.currentTime<introSeg.end){" +
            "skipBtn.style.display='block';}else{skipBtn.style.display='none';}});}}" +
            // Last chapter boundary triggers Up Next if within last 15% of duration
            "if(chapData&&chapData.length>0&&typeof startCountdown==='function'){" +
            "var lastCh=chapData[chapData.length-1];" +
            "var upNextBound=lastCh.start;" +
            "v.addEventListener('timeupdate',function(){" +
            "if(!v.duration||v.duration<=0)return;" +
            "if(upNextBound/v.duration<0.85)return;" +
            "if(v.currentTime>=upNextBound){startCountdown();}});}" +
            "}).catch(function(){});",
            this@VideoPlayerDialog.element // $0 for @ClientCallable access
        )
    }

    companion object {
        /**
         * Given a transcode ID for a TV episode, finds the next episode (same title, next in
         * season/episode order) that has a playable transcode. Skips episodes without playable
         * transcodes. Returns null for movies or if no next playable episode exists.
         */
        fun findNextPlayableEpisode(currentTranscodeId: Long): NextEpisodeInfo? {
            val currentTranscode = Transcode.findById(currentTranscodeId) ?: return null
            val currentEpisodeId = currentTranscode.episode_id ?: return null
            val currentEpisode = Episode.findById(currentEpisodeId) ?: return null

            val titleId = currentTranscode.title_id

            // All episodes for this title, sorted by season then episode number
            val allEpisodes = Episode.findAll()
                .filter { it.title_id == titleId }
                .sortedWith(compareBy({ it.season_number }, { it.episode_number }))

            val currentIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
            if (currentIndex < 0) return null

            val titleTranscodes = Transcode.findAll().filter { it.title_id == titleId }
            val nasRoot = TranscoderAgent.getNasRoot()

            // Walk forward through episodes looking for the next playable one
            for (i in (currentIndex + 1) until allEpisodes.size) {
                val nextEp = allEpisodes[i]
                val tc = titleTranscodes.firstOrNull { it.episode_id == nextEp.id } ?: continue
                val filePath = tc.file_path ?: continue

                val canPlay = if (TranscoderAgent.needsTranscoding(filePath)) {
                    nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
                } else true

                if (canPlay) {
                    val seasonEp = "S%02dE%02d".format(nextEp.season_number, nextEp.episode_number)
                    val label = nextEp.name?.let { "$seasonEp \u2014 $it" } ?: seasonEp
                    return NextEpisodeInfo(tc.id!!, label)
                }
            }

            return null
        }
    }
}
