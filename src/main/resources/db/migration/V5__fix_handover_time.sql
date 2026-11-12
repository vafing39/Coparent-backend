-- Heure de bascule : jusqu'ici, Hibernate convertissait l'heure de Paris en UTC (hibernate.jdbc.time_zone)
-- au 1er janvier 1970, soit une heure de moins (17:50 enregistré 16:50). L'entité l'enregistre désormais
-- telle quelle ; on rajoute l'heure aux lignes existantes, en repassant par minuit (23:30 → 00:30).
UPDATE custody_schedules
SET handover_time = SEC_TO_TIME(MOD(TIME_TO_SEC(handover_time) + 3600, 86400));
