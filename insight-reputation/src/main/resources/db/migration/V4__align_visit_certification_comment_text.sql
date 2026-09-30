-- The legacy schema stores visit comments as TEXT. This repairs the pre-freeze
-- TINYTEXT transition without reducing the legacy storage capacity.
ALTER TABLE visit_certification
    MODIFY COLUMN comment_content TEXT;
