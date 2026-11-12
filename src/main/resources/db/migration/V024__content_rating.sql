-- Add content rating (MPAA/TV) to titles and rating ceiling to users for parental controls
ALTER TABLE title ADD COLUMN content_rating VARCHAR(10) DEFAULT NULL;
ALTER TABLE app_user ADD COLUMN rating_ceiling INT DEFAULT NULL;
