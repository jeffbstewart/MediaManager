-- Remove COLLECTION-sourced tags now that collections have a first-class UI (#48).
-- Delete title_tag associations first (FK), then the tags themselves.
DELETE FROM title_tag WHERE tag_id IN (SELECT id FROM tag WHERE source_type = 'COLLECTION');
DELETE FROM tag WHERE source_type = 'COLLECTION';
