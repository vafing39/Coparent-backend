# Cahier des charges — Backend, priorité 1

## Principes
- Le backend est la seule source de vérité. Il vérifie tout : droits, calcul de la garde, visibilité, immuabilité des messages. L'app ne fait qu'afficher et envoyer.
- API REST documentée en OpenAPI 3. Le fichier OpenAPI est exposé et versionné ; l'app génère ses types à partir de lui.
- Temps réel par WebSocket authentifié (messages, statuts de lecture, éléments « À traiter »).
- Dates stockées en UTC, calculées et renvoyées pour le fuseau Europe/Paris (bascules de garde, rappels, changements d'heure).
- Montants en centimes (entiers), dès la priorité 2.
- Trois environnements : développement, préproduction, production.
- Hébergement dans l'Union européenne. Les fiches enfants contiennent des données de santé (allergies, médecin) : faire vérifier par un juriste si un hébergement certifié HDS est nécessaire.

## 1. Comptes et authentification
- Inscription par email et mot de passe : 12 caractères minimum, hachage Argon2id ou bcrypt, refus des mots de passe connus comme compromis.
- Connexion Apple et Google : le jeton d'identité est vérifié côté serveur. Si l'email existe déjà, les comptes sont rattachés ; sinon un compte est créé.
- Jetons de session : access token de courte durée (15 min) et refresh token à rotation, révocable. Déconnexion = révocation du refresh token.
- Vérification de l'email : code à 8 chiffres saisi dans l'app, à usage unique, valable 15 minutes, 5 essais au plus, avec renvoi limité. Les comptes Apple et Google sont considérés comme vérifiés.
- Mot de passe oublié : lien à usage unique valable 30 minutes. La réponse est identique que le compte existe ou non. Un changement de mot de passe révoque toutes les sessions.
- Limitation de débit sur la connexion, l'inscription, le mot de passe oublié et les renvois d'email.
- Export des données personnelles (RGPD) : archive générée de façon asynchrone, lien temporaire.
- Suppression du compte :
  - désactivation immédiate et suppression des données personnelles sous 30 jours ;
  - les messages envoyés restent visibles par l'autre parent, l'auteur étant affiché comme « Compte supprimé » ou par son prénom figé ;
  - la famille, les enfants et le calendrier restent rattachés à l'autre parent ;
  - les données d'un parent seul dans sa famille sont supprimées intégralement ;
  - l'abonnement n'est pas annulé côté store : l'API renvoie l'information pour que l'app l'indique.

## 2. Familles et enfants
- Entités : Famille (nom, zone de vacances A, B, C ou autre), Membres (rôle : parent A ou B ; famille élargie et professionnels en priorité 2), Enfants (prénom, date de naissance).
- Une famille compte au plus deux parents.
- Chaque requête vérifie que l'utilisateur est membre de la famille ciblée et que son rôle l'autorise.
- Mode solo : une famille avec un seul parent est pleinement fonctionnelle. Les décisions qui demandent l'accord des deux parents (rythme, membres) sont mises en file et soumises au second parent à son arrivée.

## 3. Invitations
- Lien d'invitation à jeton aléatoire, à usage unique, valable 7 jours. On peut le renouveler, ce qui invalide le précédent.
- Avant l'acceptation, l'invité ne voit qu'un aperçu : prénom de l'invitant, prénoms et âges des enfants, rythme proposé.
- À l'acceptation, l'invité devient parent B. Le rythme reste « proposé » jusqu'à sa validation ; il peut proposer une modification.
- Une notification est envoyée à l'invitant.

## 4. Moteur de garde
- Règles stockées :
  - rythme : une semaine sur deux, 2-2-3, 2-2-5-5, week-ends alternés + mercredi, ou personnalisé (séquence de jours) ;
  - date de départ, jour et heure de bascule ;
  - parent de départ.
- Vacances :
  - calendrier officiel des vacances scolaires par zone, importé et mis à jour chaque année ;
  - petites vacances partagées par moitié, l'ordre alternant selon l'année paire ou impaire ;
  - été par quinzaines ou par mois ;
  - les vacances priment sur le rythme habituel.
- Exceptions : échanges acceptés, ajustements ponctuels. Elles priment sur les règles.
- Endpoint de calcul : pour une période, il renvoie jour par jour le parent de nuit et les bascules (date, heure, de qui vers qui). Le résultat est mis en cache et invalidé à chaque changement de règle ou d'exception.
- Toute modification d'une règle de garde demande l'accord des deux parents, sauf en mode solo.
- Historique de toutes les modifications (qui, quand, avant, après).

## 5. Événements
- Champs : titre, type (rendez-vous, activité, école, autre), enfants concernés, début et fin (ou journée entière), lieu, rappel, visibilité (partagé ou privé), auteur.
- Visibilité appliquée côté serveur : un événement privé n'est jamais renvoyé à l'autre parent, ni par l'API, ni par WebSocket, ni dans un export, ni dans l'espace enfant.
- Répétition :
  - règle de type RRULE (quotidienne, hebdomadaire, toutes les N semaines, mensuelle par date ou par rang, jours de la semaine, horaire par jour, fin par date ou par nombre) ;
  - filtre « seulement quand l'enfant est chez » un parent, évalué à partir du moteur de garde ;
  - exclusion des vacances scolaires de la zone ;
  - les occurrences sont recalculées quand la garde change.
- Modification ou suppression d'une série : cette occurrence, celle-ci et les suivantes (scission de la série), ou toute la série. Les occurrences passées ne sont jamais modifiées.
- Historique des modifications, visible par les deux parents pour les événements partagés.
- Rappels planifiés côté serveur et envoyés en push à l'heure prévue (Europe/Paris).

## 6. Demandes d'échange de garde
- Un parent propose un échange de jours ou de périodes, avec un message.
- Statuts : en attente, acceptée, déclinée, expirée.
- À l'acceptation, une exception est créée dans le moteur de garde et le calendrier est recalculé pour les deux parents.
- Le demandeur est notifié de la réponse.

## 7. Messagerie
- Une conversation par famille, entre les deux parents.
- Messages strictement immuables : aucun endpoint de modification ou de suppression ; protection au niveau de la base, avec un horodatage serveur.
- Chaque message reçoit une empreinte (hash chaîné avec le précédent), qui servira à prouver l'intégrité de l'export PDF en priorité 3.
- Statut « envoyé » puis « lu » avec l'heure. Le statut lu est émis par le destinataire.
- Diffusion en temps réel par WebSocket, avec un mode de rattrapage (pagination par curseur).
- En mode solo, la messagerie est fermée tant que le second parent n'a pas rejoint.

## 8. « À traiter »
- Endpoint agrégé : tout ce qui attend une réponse de l'utilisateur (échanges, validations, et en priorité 2 : dépenses, membres, retraits de publication), et tout ce qui attend l'autre parent, avec la date de la demande.
- Règles sans réponse appliquées par des tâches planifiées (par exemple : une publication reste masquée après 14 jours).
- Relance de l'autre parent : au plus une par jour et par élément. Le texte est généré par le serveur et toujours neutre.

## 9. Notifications push
- Enregistrement des jetons d'appareil (FCM pour Android, APNs pour iOS), plusieurs appareils par utilisateur, nettoyage des jetons invalides.
- Types : nouveau message, passage de garde le lendemain, demande ou réponse en attente, rappel d'événement. Nouvelle dépense et nouvelle publication arrivent en priorité 2.
- Préférences par type et par utilisateur, respectées côté serveur.
- Textes rédigés côté serveur, neutres et factuels, sans contenu sensible sur l'écran verrouillé. Par exemple « Nouveau message de Julien », jamais le contenu du message.

## 10. Réglages
- Stockés par utilisateur : préférences de notification, thème, inversion des couleurs des parents (préférence personnelle, sans effet sur l'autre parent).

## 11. Sécurité et journalisation
- Contrôle d'accès systématique par famille et par rôle, testé pour chaque endpoint.
- Journal d'audit des actions sensibles : changements de garde, acceptations, suppressions, connexions.
- Chiffrement en transit (TLS) et au repos.
- Aucune donnée personnelle dans les journaux applicatifs ni dans Sentry.

## 12. Tests
- Tests unitaires du moteur de garde : chaque rythme, bascules, vacances par zone, années paires et impaires, changements d'heure, exceptions.
- Tests unitaires de la répétition : filtre par parent, exclusion des vacances, scission de série.
- Tests d'autorisation : un parent ne peut ni lire ni modifier les données d'une autre famille ; un événement privé n'est jamais renvoyé à l'autre parent ; un message ne peut être ni modifié ni supprimé.
- Tests d'intégration des parcours : inscription, invitation et acceptation, calcul du calendrier, envoi et lecture d'un message, suppression de compte.
