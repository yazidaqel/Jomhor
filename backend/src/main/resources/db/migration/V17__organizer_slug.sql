-- Multi-club (plan multi-club BE-37): the club directory handle, exactly as docs/03-schema.sql organizers.slug.
-- null = unlisted: not in /fan/organizers nor in "Tout" (docs/04-api.md §Club directory). Set by ops via SQL.
alter table organizers add column slug text unique
  constraint organizers_slug check (
    slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) between 2 and 40
    and slug not in ('all', 'api', 'apple-icon', 'ar', 'club', 'clubs', 'en', 'events', 'favicon', 'fr',
                     'help', 'icon', 'legal', 'login', 'logout', 'manifest', 'me', 'mine',
                     'opengraph-image', 'orders', 'robots', 'sitemap', 'twitter-image'));
                                                    -- reserved: web shop top-level paths, ?club= values, locale prefixes
