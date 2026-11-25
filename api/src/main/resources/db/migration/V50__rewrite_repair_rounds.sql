-- Rewrite repair rounds (ADR-050): how many times the model was asked for one press of "Gợi ý sửa", and in which round the first safe
-- suggestion appeared, so the statistics page can say whether a second or third round is worth its extra provider requests.
-- Additive: two columns on rewrite_suggestion_run (V42). Counts only: no title, tag, prompt, model answer or reason is stored.
--
-- Every row that exists was made with the single call of ADR-037, so rounds_used is 1 for all of them and safe_round is 1 where the run
-- had a safe suggestion. With max-rounds 1 (the default) new rows get exactly those values.
ALTER TABLE rewrite_suggestion_run
    ADD COLUMN rounds_used SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN safe_round  SMALLINT;

UPDATE rewrite_suggestion_run SET safe_round = 1 WHERE candidates_safe > 0;

ALTER TABLE rewrite_suggestion_run
    ADD CONSTRAINT ck_rewrite_run_rounds CHECK (rounds_used BETWEEN 1 AND 3),
    ADD CONSTRAINT ck_rewrite_run_safe_round CHECK ((safe_round IS NULL) = (candidates_safe = 0)
        AND (safe_round IS NULL OR safe_round BETWEEN 1 AND rounds_used));
