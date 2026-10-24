-- B3: what the student liked / rejected among past recommendations, summarised into the profile.
-- Nullable so existing snapshots stay valid; readers treat NULL as an empty list.
ALTER TABLE user_profile_snapshots
    ADD COLUMN recommendation_likes_json LONGTEXT NULL,
    ADD COLUMN recommendation_avoids_json LONGTEXT NULL;
