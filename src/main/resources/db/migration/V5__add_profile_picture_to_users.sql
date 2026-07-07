-- Optional user profile picture, stored as a base64-encoded (compressed) image.
-- NULL when the user has not set one.
ALTER TABLE users ADD COLUMN profile_picture TEXT;
