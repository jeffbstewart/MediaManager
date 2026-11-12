-- Track which version of the mobile encoder preset produced each ForMobile
-- output. When the current preset constant in code is bumped, existing rows
-- with a lower version are picked up as re-transcode candidates at lowest
-- priority (see TranscodeLeaseService).
--
-- 0 = never transcoded for mobile (default for rows with for_mobile_available = FALSE)
-- 1 = initial preset: ABR at fixed 5 Mbps, 1080p cap. Produced bloated SD output.
-- 2 = current preset: CQ/CRF 23 with 5 Mbps maxrate cap, 720p cap. Content-adaptive.
ALTER TABLE transcode ADD COLUMN mobile_encoder_version INT NOT NULL DEFAULT 0;

-- Every existing ForMobile output was produced by the v1 preset.
UPDATE transcode
   SET mobile_encoder_version = 1
 WHERE for_mobile_available = TRUE;
