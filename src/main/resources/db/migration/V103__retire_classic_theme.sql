-- The classic web theme is retired; marquee is the only theme and the
-- default. Users who had pinned classic follow the default again (NULL),
-- which renders as marquee today and moves with the default if a later
-- release changes it. A pin is an explicit choice, and nobody chose
-- marquee by having chosen classic.
UPDATE app_user SET web_theme = NULL WHERE web_theme = 'classic';
