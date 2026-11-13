-- Validation du rythme par l'autre parent (cahier des charges, 3) : chaque rythme proposé à valider devient une
-- demande « À traiter » (CUSTODY_SCHEDULE, target_id = le rythme). Les familles déjà à deux parents dont le rythme
-- attend encore sa validation reçoivent cette demande, adressée au parent qui ne l'a pas proposé.
INSERT INTO to_do_items (family_id, kind, target_id, subject, requested_by, assignee_id)
SELECT s.family_id,
       'CUSTODY_SCHEDULE',
       s.id,
       CONCAT('Rythme : ', CASE s.pattern
           WHEN 'ALTERNATE_WEEKS' THEN 'une semaine sur deux'
           WHEN 'TWO_TWO_THREE' THEN '2-2-3'
           WHEN 'TWO_TWO_FIVE_FIVE' THEN '2-2-5-5'
           WHEN 'ALTERNATE_WEEKENDS_WEDNESDAY' THEN 'week-ends alternés et mercredi'
           ELSE 'personnalisé' END),
       s.proposed_by,
       m.user_id
FROM custody_schedules s
JOIN family_members m ON m.family_id = s.family_id AND NOT (m.user_id <=> s.proposed_by)
WHERE s.status = 'PROPOSED'
  AND s.created_at = (SELECT MAX(p.created_at) FROM custody_schedules p
                      WHERE p.family_id = s.family_id AND p.status = 'PROPOSED')
  AND NOT EXISTS (SELECT 1 FROM custody_schedules a WHERE a.family_id = s.family_id AND a.status = 'ACTIVE')
  AND (SELECT COUNT(*) FROM family_members c WHERE c.family_id = s.family_id) = 2;

-- Demande fictive sans rythme associé (données de démonstration) : elle ne peut pas s'ouvrir.
DELETE FROM to_do_items WHERE kind = 'CUSTODY_SCHEDULE' AND target_id IS NULL;
