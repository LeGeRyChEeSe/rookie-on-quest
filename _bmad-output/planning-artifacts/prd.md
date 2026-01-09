---
stepsCompleted: ['step-01-init', 'step-02-discovery', 'step-03-success', 'step-04-journeys', 'step-05-domain', 'step-06-innovation', 'step-07-project-type', 'step-08-scoping', 'step-09-functional', 'step-10-nonfunctional', 'step-11-complete']
inputDocuments:
  - 'docs/index.md'
  - 'docs/architecture-app.md'
  - 'docs/data-models-app.md'
  - 'docs/api-contracts-app.md'
  - 'docs/state-management-app.md'
workflowType: 'prd'
lastStep: 11
briefCount: 0
researchCount: 0
brainstormingCount: 0
projectDocsCount: 5
workflowStatus: 'complete'
completedDate: '2026-01-09'
---

# Product Requirements Document - rookie-on-quest

**Author:** Garoh
**Date:** 2026-01-09

## Executive Summary

**Rookie On Quest** est une application Android standalone pour casques Meta Quest VR permettant aux utilisateurs de parcourir, télécharger et installer des jeux VR nativement sans PC. Ce PRD définit des corrections de bugs critiques et des améliorations UX majeures pour transformer l'application d'un "outil fonctionnel" à une **expérience VR native sans friction**.

### Contexte du Projet

**Type :** Brownfield - Améliorations d'une application Android existante (v2.4.0)
**Architecture actuelle :** MVVM avec Kotlin + Jetpack Compose, Single-Activity, Room Database, StateFlow pour gestion d'état réactive
**Contrainte environnementale critique :** Meta Quest VR avec CPU limité, utilisateur immergé, absence de PC de secours

### Problèmes à Résoudre

Ce PRD adresse **5 problématiques critiques** identifiées dans les issues GitHub (#16, #15) et par retours utilisateurs :

**🐛 Bugs Critiques :**

**1. #16 - Perte de l'état de téléchargement au redémarrage**
- **Problème :** La queue d'installation (stockée uniquement en mémoire via `StateFlow`) se perd complètement au redémarrage de l'application. L'utilisateur perd sa progression de téléchargement, doit recommencer depuis zéro.
- **Impact utilisateur :** Frustration majeure en contexte VR où l'utilisateur n'a pas de PC de secours. Perte de confiance dans la fiabilité de l'application.
- **Scénario typique :** Utilisateur lance download de 8GB → ferme app pour jouer à un autre jeu → revient 30min plus tard → queue vide, tout perdu.

**2. #15 - Tri par taille instable avec liste qui "saute"**
- **Problème :** Le tri par taille provoque un "saut" visuel continu de la liste pendant le scroll. Causé par l'optimisation intentionnelle de lazy loading des métadonnées (fetch uniquement pour items visibles à l'écran pour économiser CPU Quest).
- **Impact utilisateur :** Expérience utilisateur chaotique, impossible de naviguer efficacement la liste triée.
- **Contexte technique :** Optimisation CPU nécessaire pour Quest (éviter milliers de requêtes réseau simultanées).

**✨ Améliorations UX Majeures :**

**3. #17 - Feedback de progression insuffisant et anxiogène**
- **Problème actuel :** Barre de progression reste bloquée (~87%) pendant l'extraction 7z sans indication de l'état réel. Peut durer plusieurs minutes sans feedback visuel. Messages génériques ne distinguent pas "download only" vs "installation complète".
- **Impact utilisateur :** Anxiété ("est-ce que ça a planté ?"), perception du temps dilatée, confusion sur l'état réel de l'installation.
- **Besoin psychologique :** En VR, l'utilisateur ne peut pas "alt-tab" vérifier autre chose. Le feedback visuel continu est critique pour maintenir la confiance.

**4. #18 - Absence de notifications de fin d'installation**
- **Problème :** Aucune notification sonore ou visuelle quand une installation se termine. Impossible pour l'utilisateur de multitâcher efficacement.
- **Impact utilisateur :** Utilisateur doit rester dans l'app et surveiller manuellement, ou deviner quand c'est terminé.
- **Scénario bloqué :** Lancer download → jouer à Beat Saber en attendant → ne jamais savoir que c'est terminé.

**5. #19 - Friction de confirmation d'installation (Shizuku)**
- **Problème :** Chaque APK nécessite une confirmation manuelle via le système Android, obligeant l'utilisateur à enlever son casque Meta Quest à chaque installation.
- **Impact utilisateur :** Casse l'immersion VR, friction majeure pour installations batch (télécharger 5 jeux = 5 fois enlever le casque).
- **Solution proposée :** Intégration optionnelle avec Shizuku pour installation silencieuse sans confirmation.

### Solutions Proposées

#### Phase 1 - Fiabilité & Persistance (Bug #16)

**Architecture : Migration vers Room-backed StateFlow + WorkManager**

**Décision architecturale :**
- Migrer de `MutableStateFlow<List<InstallTaskState>>` (mémoire volatile) vers Room Database comme source de vérité
- Ajouter WorkManager pour garantie de reprise automatique après redémarrage système

**Nouvelle entité Room :**
```kotlin
@Entity(tableName = "install_queue")
data class QueuedInstallEntity(
    @PrimaryKey val releaseName: String,
    val status: String, // QUEUED, DOWNLOADING, EXTRACTING, COPYING_OBB, INSTALLING
    val progress: Float,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val queuePosition: Int,
    val createdAt: Long,
    val lastUpdatedAt: Long
)
```

**Flow de données :**
1. User clicks install → Insert dans Room → Trigger WorkManager
2. WorkManager exécute download/extraction → Update Room en temps réel avec progress
3. ViewModel observe Room via `Flow<List<QueuedInstallEntity>>` → transforme en `StateFlow<List<InstallTaskState>>`
4. UI se met à jour automatiquement via Compose `.collectAsState()`
5. **App restart** → WorkManager reprend automatiquement → Room déjà à jour → UI restaure l'état instantanément

**Nouvelles dépendances :**
- `androidx.work:work-runtime-ktx:2.9.0` (WorkManager)
- Configuration WorkManager : `Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true)`

**Résultat utilisateur :** Confiance totale - "Je peux fermer l'app, redémarrer mon Quest, mes téléchargements continuent et je retrouve toujours l'état exact."

#### Phase 2 - Feedback Utilisateur Amélioré (Features #17 & #18)

**A. Système de progression à deux niveaux avec animation stickman**

**Concept UX :** Transformer la barre de progression ennuyeuse en expérience mémorable et rassurante avec un personnage stickman animé.

**Composants visuels :**

**1. Animation Stickman (Progression Locale) :**
- **Style :** Minimaliste, ligne noire simple (2-3px stroke), style xkcd/générique
- **Taille :** ~80-100dp (visible sans être intrusif)
- **Animations fluides 60fps**

**États par opération :**
- **Downloading :** Court sur escaliers, bras en mouvement alterné, petit nuage de poussière aux pieds
- **Extracting :** Ralentit, ouvre "boîte invisible", sort items, les empile sur marches
- **Copying OBB :** Porte grosse boîte, dos légèrement courbé (effort visible)
- **Installing APK :** Sprint final vers sommet, jump victorieux !
- **Pause longue (>2min) :** S'assoit, essuie front, boit de l'eau, reprend après 3-4 sec (humanise l'attente)

**2. Cercle de Progression Globale :**
- **Position :** Entoure l'animation stickman (non intrusif)
- **Style :** Stroke fin (4dp), couleur Material 3 primary adaptée au theme
- **Animation :** Smooth arc progression (0-360°) avec easing curve
- **Label :** "Étape 2/4" ou pourcentage global affiché discrètement sous le stickman

**Progression globale calculée :**
```
Étape 1/4 : Downloading (0-40%)
Étape 2/4 : Extracting (40-70%)
Étape 3/4 : Copying OBB (70-85%)
Étape 4/4 : Installing APK (85-100%)
```

**3. Messages contextuels adaptés :**
- **Mode "Install"** : "Installation terminée ! Beat Saber est prêt 🎉"
- **Mode "Download Only"** : "Téléchargement et extraction terminés ! Fichiers dans /Download/RookieOnQuest/Beat-Saber/"

**B. Notifications de fin d'installation (Feature #18)**

**Stratégie non intrusive :**

**Pendant un jeu VR actif :**
- **Notification Quest standard** (barre overlay en haut du champ de vision)
- **Son doux** : Success chime court (<1sec, volume modéré) - PAS agressif pour horror games
- **Message :** "Rookie On Quest - Beat Saber installé ✓"

**App Rookie active :**
- **Animation confetti** autour du stickman victorieux
- **Message overlay :** "Installation terminée ! 🎉"

**Configuration utilisateur :**
- **Settings toggle :** "Notifications sonores" ON/OFF
- Par défaut : ON (mais son doux non intrusif)

#### Phase 3 - Tri Intelligent par Taille (Bug #15)

**Solution : Désactivation conditionnelle intelligente**

**Problème root cause :**
- Tri par taille nécessite métadonnées (sizeBytes) pour tous les jeux
- Lazy loading actuel fetch seulement items visibles (optimisation CPU Quest)
- Tri sur données partielles → items changent de position quand métadonnées arrivent → "saut" visuel

**Solution retenue :** Désactiver temporairement le tri par taille tant que <80% des métadonnées ne sont pas chargées.

**Implémentation :**

**1. Nouveau StateFlow pour tracking progress :**
```kotlin
private val _metadataLoadProgress = MutableStateFlow(0f)

val metadataLoadProgress: StateFlow<Float> = _metadataLoadProgress
    .map { loadedCount / totalGamesCount.toFloat() }
    .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
```

**2. Disponibilité conditionnelle du tri :**
```kotlin
sealed class SortAvailability {
    object Available : SortAvailability()
    data class Disabled(val reason: String, val progress: Float) : SortAvailability()
}

val sortBySizeAvailability: StateFlow<SortAvailability> =
    metadataLoadProgress.map { progress ->
        if (progress >= 0.8f) {
            SortAvailability.Available
        } else {
            SortAvailability.Disabled(
                reason = "Disponible après synchronisation",
                progress = progress
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SortAvailability.Disabled("", 0f))
```

**3. UI Behavior :**
- Option menu "Trier par taille" affiche badge grisé : "🔄 Sync... 65%" tant que désactivé
- Au tap → Tooltip : "Les tailles sont chargées progressivement. Synchronisation en cours."
- Une fois 80% atteint → Badge disparaît, tri s'active automatiquement

**Trade-offs acceptés :**
- ✅ Zéro "saut" visuel - UX prévisible et stable
- ✅ CPU Quest économisé - Pas de fetch massif simultané
- ⚠️ Tri indisponible temporairement (~10-30 secondes) - Mais UX claire avec feedback progress

#### Phase 4 - Installation Silencieuse via Shizuku (Feature #19) - OPTIONNEL

**Statut :** Feature avancée, Phase 4 (dernière priorité), optionnelle

**Prérequis utilisateur :**
1. Installer l'app Shizuku depuis SideQuest ou GitHub
2. Activer Wireless Debugging sur Meta Quest
3. Lancer Shizuku et démarrer le service
4. Accorder permission à Rookie On Quest dans Shizuku

**Runtime detection & fallback gracieux :**
```kotlin
fun installApkSilently(apkFile: File): Boolean {
    return if (Shizuku.pingBinder() &&
               Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        // Installation silencieuse via Shizuku
        installViaShizuku(apkFile)
        true
    } else {
        // Fallback: Installation classique avec FileProvider
        installViaFileProvider(apkFile)
        false
    }
}
```

**Dépendances :**
```gradle
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```

### Ce qui Rend Cette Amélioration Spéciale

**Compréhension profonde de l'environnement VR Quest :**

1. **Contraintes matérielles :** CPU limité du Quest nécessite optimisations intelligentes (lazy loading, désactivation conditionnelle du tri)
2. **Contexte d'usage VR :** Utilisateur immergé → notifications critiques mais non intrusives, feedback visuel continu essentiel
3. **Absence de PC :** Fiabilité et persistance non-négociables (WorkManager + Room), pas de plan B
4. **UX psychologique :** Animation stickman transforme l'anxiété de l'attente en expérience engageante et rassurante

**Impact utilisateur attendu :**
- ✅ "Je peux faire confiance aux téléchargements, même si je ferme l'app"
- ✅ "Je sais exactement où en est mon installation, plus d'anxiété"
- ✅ "Je peux trier par taille sans que la liste devienne folle"
- ✅ "Je suis notifié quand c'est terminé, je peux multitâche en VR"
- ✅ "Je n'ai plus besoin d'enlever mon casque pour confirmer chaque jeu"

### Vision Produit

Transformer **Rookie On Quest** de "gestionnaire de jeux VR fonctionnel" à **l'expérience native Quest de référence** - fiable, intuitive, optimisée pour VR, psychologiquement rassurante, et sans friction.

## Project Classification

**Technical Type :** Mobile App (Android Native - Meta Quest VR)
**Domain :** Gaming (VR Game Distribution)
**Complexity :** Medium
**Project Context :** Brownfield - extending existing MVVM Kotlin application (v2.4.0)

**Architecture existante à respecter :**
- **Pattern MVVM :** MainViewModel + MainRepository + Room Database
- **State Management :** StateFlow pour état réactif, SharedFlow pour événements one-time
- **Single-Activity Compose :** Toute l'UI dans MainActivity (~1400 lignes)
- **Queue Processing :** Coroutine job séquentiel dans viewModelScope

**Nouvelles intégrations techniques :**
- **WorkManager 2.9.0+** : Persistance downloads/installations, reprise automatique
- **Room Database extension** : Nouvelle table `install_queue`
- **Android Notifications API** : Notifications sonores/visuelles contextuelles
- **Jetpack Compose Animations** : Animation state machine stickman (60fps)
- **Shizuku SDK 13.1.5** (optionnel) : Installation silencieuse APK

## Success Criteria

### User Success

**Vision :** Zéro friction, zéro gêne à aucun moment lors de l'utilisation de Rookie On Quest.

Le succès utilisateur est mesuré par l'élimination complète de 5 "moments de gêne" identifiés dans l'expérience actuelle :

**1. Élimination de la perte de téléchargement (Bug #16)**
- ✅ **100% des downloads reprennent exactement où ils étaient** après fermeture/redémarrage de l'application
- ✅ **UI affiche immédiatement l'état restauré** (<2 secondes après ouverture app)
- ✅ **0 plainte utilisateur** concernant la perte de progression dans les issues GitHub
- **Moment de succès :** L'utilisateur lance un download de 8GB, ferme l'app pour jouer à un autre jeu, revient 30 minutes plus tard et voit "Téléchargement repris : 65%" instantanément.

**2. Élimination de l'anxiété pendant extraction (Feature #17)**
- ✅ **Stickman bouge continuellement** - jamais statique >5 secondes
- ✅ **Progression visuelle toujours en mouvement** (même si lente pendant opérations longues)
- ✅ **0 questions "est-ce que c'est planté ?"** dans support/issues GitHub
- **Moment de succès :** L'utilisateur voit le stickman grimper les escaliers pendant 3 minutes d'extraction, s'assoit pour boire de l'eau, reprend la montée - l'utilisateur sourit et attend patiemment au lieu de paniquer.

**3. Élimination de la liste instable pendant tri (Bug #15)**
- ✅ **0 "saut" visuel** quand le tri par taille est actif
- ✅ **Feedback clair** expliquant pourquoi tri est temporairement désactivé (badge sync progress visible)
- ✅ **Tri s'active automatiquement** dès que 80% des métadonnées sont chargées
- ✅ **Aucune plainte** concernant le tri instable après déploiement
- **Moment de succès :** L'utilisateur active le tri par taille, voit le badge "🔄 Sync... 65%", attend 15 secondes, le badge disparaît, la liste se trie parfaitement sans aucun mouvement chaotique.

**4. Élimination de l'incertitude sur l'état d'installation (Feature #18)**
- ✅ **Notification apparaît <3 secondes** après fin d'installation
- ✅ **Son audible mais doux** (pas de sursaut même dans un horror game)
- ✅ **L'utilisateur peut multitâcher** sans jamais vérifier manuellement l'état
- ✅ **Feedback positif** mentionnant spécifiquement les notifications dans reviews/issues
- **Moment de succès :** L'utilisateur lance 3 downloads, joue à Beat Saber pendant 20 minutes, reçoit 3 notifications successives douces, termine sa session Beat Saber, revient et lance les 3 jeux fraîchement installés immédiatement.

**5. Élimination de la friction confirmation APK (Feature #19 - Optionnel)**
- ✅ **0 confirmation manuelle** si Shizuku est activé
- ✅ **Batch install de 10 jeux sans aucune intervention** humaine
- ✅ **Fallback gracieux** si Shizuku indisponible (pas de crash, message clair expliquant le fallback)
- **Moment de succès :** L'utilisateur sélectionne 5 jeux avant d'aller dormir, active "Télécharger et installer", se réveille le matin, tous les jeux sont installés et prêts à jouer sans qu'il ait touché à quoi que ce soit.

**Indicateur global de succès utilisateur :**
Un utilisateur qui dit : *"Je peux faire confiance à Rookie On Quest. Je lance mes downloads, je joue à autre chose, je suis notifié quand c'est prêt, et tout fonctionne simplement."*

### Business Success

**Contexte :** Projet open-source - le succès business se mesure par adoption, réputation, et santé de la communauté.

**À 3 mois après déploiement (v2.5.0) :**
- ✅ **0 issues GitHub réouvrant les bugs #15, #16** (preuve que les corrections sont solides)
- ✅ **Feedback positif explicite** mentionnant le stickman animation ou la fiabilité persistante
- ✅ **Aucune régression** sur les features existantes (tous les tests d'intégration passent)
- ✅ **Diminution des questions support** concernant "download perdu" ou "est-ce que ça a planté"

**À 12 mois (v3.0.0 et au-delà) :**
- ✅ **Rookie On Quest devient la référence** dans la communauté VRPirates/Quest sideloading
- ✅ **Mentions positives** sur Reddit r/QuestPiracy et forums communautaires
- ✅ **Autres développeurs inspirés** par l'UX stickman (imitation = validation du succès)
- ✅ **Contributions communautaires** augmentent (PRs pour nouvelles features inspirées par la qualité)

**Métriques quantitatives (si tracking disponible) :**
- Nombre moyen de jeux installés par utilisateur augmente (signe de confiance et engagement)
- Temps moyen de session augmente (multitâche VR efficace)
- Taux de complétion des installations passe à >95% (vs actuel avec abandons dus aux bugs)

### Technical Success

**Performance VR (Critique - non-négociable) :**
- ✅ **UI maintient 60fps** même pendant downloads/extractions simultanées (pas de lag en VR)
- ✅ **Animation stickman garantie à 60fps** (fluidité = absence de gêne visuelle)
- ✅ **Consommation batterie** n'augmente pas de plus de 5% vs version actuelle
- ✅ **Utilisation mémoire animation** <10MB (stickman + cercle progression)

**Fiabilité (Critique - non-négociable) :**
- ✅ **100% de reprise après restart** - WorkManager + Room testés sur minimum 100 scénarios de crash/restart
- ✅ **0% de perte de données** dans la queue persistée (tests avec crash aléatoires, kill process, reboot forcé)
- ✅ **Graceful degradation garantie** - Si Shizuku fail, fallback FileProvider sans crash ni perte de données
- ✅ **Extraction 7z robuste** - Progress accuracy ±10% acceptable, mais jamais de freeze UI

**UX Quality :**
- ✅ **Toutes les animations utilisent easing curves** (pas de "snap" brutal)
- ✅ **Tous les messages sont contextuels** (mode "Download Only" vs "Install" correctement différenciés)
- ✅ **Settings minimalistes** (1 toggle "Notifications sonores", rien de plus)
- ✅ **Accessibilité** - Notifications visuelles + sonores (utilisateur peut désactiver son)

**Architecture Quality :**
- ✅ **Migration Room + WorkManager sans régression** sur fonctionnalités existantes
- ✅ **Backwards compatibility** - Ancien format queue en mémoire migré automatiquement vers Room
- ✅ **Tests automatisés** couvrant :
  - Persistance queue (unit tests Room DAO)
  - WorkManager reprise (instrumented tests avec process kill)
  - Animation stickman (screenshot tests Compose)
  - Tri conditionnel (unit tests ViewModel logic)

### Measurable Outcomes

**Validation quantitative du succès :**

| Critère | Métrique Actuelle (v2.4.0) | Cible Post-Fix (v2.5.0) | Méthode de Mesure |
|---------|---------------------------|------------------------|-------------------|
| **Persistance queue** | 0% (tout perdu au restart) | 100% (reprise garantie) | Tests automatisés + monitoring issues GitHub |
| **Anxiété extraction** | Plaintes fréquentes (~10 issues) | 0 plaintes post-déploiement | GitHub issues tracking |
| **Stabilité tri** | Liste "saute" continuellement | 0 saut visuel après activation | Tests UI + feedback utilisateurs |
| **Multitâche VR** | Impossible (pas de notifications) | 100% utilisateurs notifiés <3s | Telemetry (si ajoutée) ou feedback qualitatif |
| **Friction APK** | 5 confirmations pour 5 jeux | 0 confirmation si Shizuku actif | User reports |
| **Performance UI** | 60fps stable | 60fps maintenu avec stickman | Frame profiling Android Studio |
| **Engagement** | ~3 jeux/utilisateur (estimation) | >5 jeux/utilisateur | Analytics (si disponible) |

**Validation qualitative :**
- ✅ Feedback utilisateur mentionne spécifiquement : "fiable", "rassurant", "smooth", "j'adore le petit bonhomme"
- ✅ 0 demande de rollback vers v2.4.0
- ✅ Discussions communautaires positives sur Reddit/Discord

## Product Scope

### MVP - Minimum Viable Product (v2.5.0 - Release Target)

**Ce qui DOIT être livré pour que le produit soit viable et élimine les gênes critiques :**

**Phase 1 - Fiabilité & Persistance (Priorité P0) :**
- ✅ **Bug #16 - Persistance queue d'installation**
  - Migration StateFlow → Room Database (table `install_queue`)
  - Intégration WorkManager pour reprise automatique
  - UI restaure état <2 secondes après ouverture app
  - Tests : 100 scénarios crash/restart validés

**Phase 2 - Feedback Utilisateur (Priorité P0) :**
- ✅ **Feature #17 - Animation stickman progression**
  - Stickman avec 5 états animés (downloading, extracting, copying OBB, installing, pause)
  - Cercle progression globale (4 étapes)
  - Messages contextuels (download-only vs install)
  - Tests : 60fps garanti sur Quest 2/3

- ✅ **Feature #18 - Notifications fin installation**
  - Notification Quest standard (overlay VR)
  - Son doux configurable (toggle settings)
  - Apparition <3 secondes post-installation
  - Tests : Notifications en jeu + app active

**Phase 3 - Performance Stable (Priorité P1) :**
- ✅ **Bug #15 - Tri intelligent par taille**
  - StateFlow `metadataLoadProgress` tracking
  - Désactivation conditionnelle tri si <80% métadonnées
  - Badge UI "🔄 Sync... X%" avec tooltip explicatif
  - Activation automatique à 80%
  - Tests : Pas de saut visuel après activation

**Critères de release MVP :**
- ✅ Tous les bugs P0/P1 résolus et testés
- ✅ Aucune régression sur features existantes
- ✅ Tests automatisés passent à 100%
- ✅ Beta testée par minimum 5 utilisateurs communauté avec feedback positif
- ✅ Documentation mise à jour (README, CHANGELOG)

**Timeline estimée MVP :** 4-6 semaines (selon disponibilité développeur)

### Growth Features (Post-MVP - v2.6.0+)

**Features qui améliorent l'expérience mais ne sont pas bloquantes pour le MVP :**

**Phase 4 - Installation Silencieuse (Priorité P2 - Optionnel) :**
- ✅ **Feature #19 - Intégration Shizuku**
  - Détection runtime Shizuku disponibilité
  - Installation silencieuse APK sans confirmation
  - Fallback gracieux vers FileProvider si indisponible
  - Documentation setup Shizuku pour utilisateurs avancés
  - Tests : Batch install 10 jeux sans intervention

**Pourquoi post-MVP :**
- Setup complexe pour utilisateurs (Shizuku + wireless debugging)
- Pas critique pour éliminer les gênes principales
- Public cible : utilisateurs avancés (minorité)
- Fallback vers méthode classique acceptable

**Autres Growth Features potentielles (v2.7.0+) :**
- Variantes stickman (thèmes : robot, astronaute, ninja) - customisation fun
- Statistiques installation (temps moyen, espace économisé, etc.)
- Améliorations performance (pré-fetch intelligent métadonnées)
- Export/import favoris (backup utilisateur)

### Vision (Future - v3.0.0+)

**Fonctionnalités aspirationnelles qui transforment Rookie On Quest en expérience next-level :**

**Synchronisation Cloud (Vision Long-Terme) :**
- Favoris synchronisés entre appareils Quest
- Historique installations préservé
- Paramètres utilisateur cloud backup

**Intelligence de Téléchargement :**
- Scheduling : "Télécharger la nuit quand Quest en charge"
- Priorisation automatique : Jeux populaires téléchargés en priorité
- Recommandations : "Basé sur vos installations, vous pourriez aimer..."

**Communauté & Social :**
- Voir quels jeux vos amis ont installés (opt-in)
- Ratings & reviews intégrés
- Collections communautaires ("Top 10 Quest 3", "Best fitness games")

**Optimisations Avancées :**
- Delta updates (télécharger seulement les fichiers modifiés)
- Compression intelligente pour économiser bande passante
- P2P sharing entre utilisateurs locaux (LAN)

**UX Next-Gen :**
- Voice commands : "Rookie, install Beat Saber"
- Gestes VR : Drag & drop pour ajouter à queue
- Preview 3D : Voir screenshots en 180° avant download

**Principe directeur Vision :** Chaque feature doit maintenir le standard "zéro gêne" établi dans le MVP. Aucune complexité ajoutée qui pourrait frustrer l'utilisateur.

## Mobile App (Android Native) Specific Requirements

### Platform & Target Devices

**Target Platform:** Android Native - Meta Quest VR Headsets Only

- **Device Compatibility:** Meta Quest 1, Quest 2, Quest 3, Quest Pro
- **Minimum SDK:** API Level 29 (Android 10) - supports oldest Quest device still in use
- **Target SDK:** API Level 34 - follows Meta platform updates
- **Architecture:** ARM64-v8a (Quest native architecture)

**Rationale:** Games distributed via Rookie On Quest are Quest-exclusive builds. No smartphone/tablet Android compatibility needed.

**SDK Evolution Strategy:** Min SDK remains fixed to support Quest 1 legacy devices. Target SDK updated with Meta platform requirements to maintain store compliance and API access.

### Device Permissions Model

**Runtime Permissions Required (Critical Flow):**

Sequential permission flow on first launch:

1. **INSTALL_UNKNOWN_APPS** - Install APKs from external sources
2. **MANAGE_EXTERNAL_STORAGE** - Access /sdcard/Download/ for game files
3. **IGNORE_BATTERY_OPTIMIZATIONS** - Ensure downloads continue during long sessions

**Standard Permissions (Manifest Only):**

- `INTERNET` - Network access for catalog sync and downloads
- `ACCESS_NETWORK_STATE` - Detect online/offline for UI adaptation
- `FOREGROUND_SERVICE` - WorkManager persistent downloads with notifications
- `WAKE_LOCK` - Keep CPU active during 7z extraction (prevents sleep during 10-15min extractions)
- `REQUEST_DELETE_PACKAGES` - Cleanup after installations
- `QUERY_ALL_PACKAGES` - Detect installed games for version comparison

**Permission Handling:**
- Sequential permission requests (one at a time, not all at once)
- Graceful fallback if user denies non-critical permissions
- Clear in-app explanation for each permission's purpose

### Offline Mode & Data Persistence

**Hybrid Online/Offline Strategy:**

**Online Mode (Preferred):**
- Full catalog browsing (1000+ games)
- Real-time metadata fetching (sizes, screenshots, descriptions)
- Download and install workflows

**Offline Mode (Graceful Degradation):**
- Display cached catalog (last sync snapshot)
- Show only pre-downloaded games ready for installation
- Install pre-downloaded games without internet
- Clear UI indication of offline status

**Persistence Layer:**
- **Room Database:** Catalog cache, favorites, game metadata
- **WorkManager:** Download queue survives app restarts and device reboots
- **Local Files:** Thumbnails, icons, game notes cached in app storage

**Network State Detection:**
`ACCESS_NETWORK_STATE` permission enables UI adaptation:
- "Offline Mode" banner when no connectivity
- Disable download buttons for non-cached games
- Show "Sync Catalog" button when connection restored

### Push Notifications Strategy

**Notification Type:** Local notifications only (no server push infrastructure)

**Notification Channels:**

1. **DOWNLOAD_PROGRESS** (Medium importance)
   - Active download progress with stickman animation preview
   - Persistent notification while downloads active
   - Dismissible only when download completes or is cancelled

2. **INSTALL_COMPLETE** (High importance)
   - Appears within 3 seconds of installation completion
   - Includes game name and success icon
   - Optional sound notification (user toggle in settings)

3. **ERRORS** (High importance)
   - Critical failures (storage full, download failed, extraction error)
   - Actionable messages with retry options

**VR Context Considerations:**
- Sound notifications: Gentle chime (<1 sec, moderate volume) - not jarring during horror games
- Visual overlay: Quest standard notification bar (top of field of vision)
- Timing: Never interrupt active gameplay with intrusive notifications

### Store Compliance & Distribution

**Distribution Method:** Sideloading via GitHub Releases + SideQuest

**NOT distributed via:**
- ❌ Google Play Store (sideloading app violates ToS)
- ❌ Meta Quest Store (sideloading enabler not permitted)

**Meta Quest Guidelines Adherence (Voluntary):**
- ✅ Performance: Maintain 60fps UI (critical for VR comfort)
- ✅ Battery Optimization: Efficient background operations, respect battery constraints
- ✅ Storage Management: Explicit user control over downloads, clear storage indicators
- ✅ Permission Transparency: Clear explanations for each permission request

**Update Mechanism:**
- GitHub API check for latest release on app startup
- In-app update prompt with changelog
- APK download and installation via FileProvider
- No forced updates (user can skip)

### VR-Specific Technical Considerations

**UI Performance Requirements:**
- **60fps guaranteed** during all operations (downloads, installations, browsing)
- Async operations for all I/O (network, disk, database)
- Compose UI optimizations for VR rendering

**Battery & Thermal Management:**
- WorkManager respects `setRequiresBatteryNotLow(true)` constraint
- Long operations (7z extraction) designed to complete before thermal throttling
- User notification if low battery detected during large downloads

**Input Methods:**
- Touch input (Quest controller pointer)
- No keyboard/mouse support needed
- Wide touch targets (48dp minimum) for VR pointer accuracy

## Functional Requirements

### Catalog Management & Discovery

- **FR1:** Users can browse the complete VRPirates game catalog with thumbnails and icons
- **FR2:** Users can search games by name
- **FR3:** Users can filter games by category/genre
- **FR4:** Users can sort games by name (alphabetical ascending/descending)
- **FR5:** Users can sort games by size when 80%+ of metadata is loaded
- **FR6:** Users can view game details including description, screenshots, size, and version
- **FR7:** Users can mark games as favorites for quick access
- **FR8:** Users can view their favorites list
- **FR9:** System can sync catalog from VRPirates mirror automatically
- **FR10:** System can detect available catalog updates and notify users

### Download & Installation Queue

- **FR11:** Users can add games to download queue
- **FR12:** Users can choose "Download Only" mode (no automatic installation)
- **FR13:** Users can choose "Download & Install" mode (automatic installation after download)
- **FR14:** Users can view current download queue with position and status
- **FR15:** Users can pause active downloads
- **FR16:** Users can resume paused downloads
- **FR17:** Users can cancel downloads at any time
- **FR18:** Users can promote queued downloads to front of queue (priority installation)
- **FR19:** System can persist download queue across app restarts and device reboots
- **FR20:** System can automatically resume interrupted downloads after restart

### Download & Extraction Operations

- **FR21:** System can download game files with HTTP range resumption support
- **FR22:** System can extract password-protected 7z archives
- **FR23:** System can handle multi-part 7z archives (game.7z.001, game.7z.002, etc.)
- **FR24:** System can verify downloaded files against server checksums
- **FR25:** System can handle special installation instructions from install.txt files
- **FR26:** System can move OBB files to correct Android directories
- **FR27:** System can perform pre-flight storage space checks before downloads

### Progress Feedback & Visualization

- **FR28:** Users can view real-time download progress with percentage and bytes downloaded
- **FR29:** Users can view animated stickman character representing current operation phase
- **FR30:** Users can see stickman animations specific to each phase (downloading, extracting, copying OBB, installing)
- **FR31:** Users can see global progress indicator showing current step (e.g., "Step 2/4")
- **FR32:** Users can view contextual messages distinguishing "Download Only" vs "Install" modes
- **FR33:** System can display stickman "pause" animation during long operations (>2min)

### Notifications & User Alerts

- **FR34:** Users can receive local notifications when installations complete
- **FR35:** Users can receive notifications with optional sound alerts (configurable)
- **FR36:** Users can enable/disable sound notifications in settings
- **FR37:** Users can receive error notifications for failed downloads or installations
- **FR38:** System can display Quest VR overlay notifications compatible with active gameplay

### Game Installation

- **FR39:** System can install APK files via Android FileProvider
- **FR40:** System can install APKs silently when Shizuku is available and authorized
- **FR41:** System can fallback to manual confirmation installation when Shizuku unavailable
- **FR42:** System can detect Shizuku availability at runtime
- **FR43:** System can clean up temporary files after installation completes
- **FR44:** System can verify installed game version against catalog version

### Offline Mode & Synchronization

- **FR45:** Users can browse cached catalog when offline
- **FR46:** Users can view pre-downloaded games ready for installation when offline
- **FR47:** Users can install pre-downloaded games without internet connection
- **FR48:** System can detect network connectivity status
- **FR49:** System can display offline mode indicator in UI
- **FR50:** System can sync catalog when connection restored

### Permission & System Integration

- **FR51:** System can request required Android permissions sequentially
- **FR52:** System can provide clear explanations for each permission request
- **FR53:** System can function with graceful degradation if optional permissions denied
- **FR54:** System can detect battery optimization status
- **FR55:** System can maintain CPU wake lock during long extractions

### Settings & Configuration

- **FR56:** Users can configure notification sound preferences
- **FR57:** Users can view app version and check for updates
- **FR58:** Users can manually trigger catalog synchronization
- **FR59:** Users can export diagnostic logs for troubleshooting
- **FR60:** System can check GitHub for app updates on startup

## Non-Functional Requirements

### Performance

**VR Frame Rate (Critical):**
- **NFR-P1:** UI must maintain 60fps during all operations (downloads, installations, catalog browsing)
- **NFR-P2:** Background operations (downloads, extractions) must not cause frame drops or UI lag
- **NFR-P3:** Stickman animations must render at 60fps consistently without stuttering

**Response Time:**
- **NFR-P4:** User interactions (tap, scroll, search) must respond within 100ms
- **NFR-P5:** Catalog sync must complete initial load within 10 seconds on first launch
- **NFR-P6:** Search results must appear within 500ms of user input

**Resource Efficiency:**
- **NFR-P7:** Memory usage for stickman animation must not exceed 10MB
- **NFR-P8:** App background memory footprint must not exceed 150MB during downloads
- **NFR-P9:** Battery consumption during downloads must not exceed 5% increase vs v2.4.0 baseline

**Extraction Performance:**
- **NFR-P10:** 7z extraction progress must update UI at minimum 1Hz (once per second)
- **NFR-P11:** CPU wake lock must prevent Quest sleep during extractions >2 minutes

### Reliability

**Data Persistence (Critical):**
- **NFR-R1:** Download queue must persist 100% across app crashes, force quits, and device reboots
- **NFR-R2:** Room Database transactions must be atomic (all-or-nothing) to prevent corrupted state
- **NFR-R3:** UI must restore complete download state within 2 seconds of app reopening

**Download Resumption:**
- **NFR-R4:** HTTP range resumption must work for interrupted downloads with 0% data loss
- **NFR-R5:** WorkManager must automatically retry failed downloads with exponential backoff (max 3 retries)
- **NFR-R6:** Partial downloads must be resumable even after device reboot

**Error Recovery:**
- **NFR-R7:** Failed extractions must clean up temp files automatically
- **NFR-R8:** Storage full errors must be detected pre-flight before download starts
- **NFR-R9:** Corrupted 7z archives must fail gracefully with clear error message and cleanup

**Crash Resilience:**
- **NFR-R10:** No installation data loss during crash (WorkManager + Room guarantee)
- **NFR-R11:** App must handle Quest system kill (low memory) without queue corruption

### Usability

**VR User Experience:**
- **NFR-U1:** All touch targets must be minimum 48dp for VR pointer accuracy
- **NFR-U2:** Critical errors must be visible and actionable without removing headset
- **NFR-U3:** Progress feedback must update continuously (no freezes >5 seconds without visual change)

**Installation Feedback:**
- **NFR-U4:** Stickman animation must change state visibly within 2 seconds of operation phase change
- **NFR-U5:** Completion notifications must appear within 3 seconds of installation success
- **NFR-U6:** Sound notifications must be audible but non-jarring (<1 second duration, moderate volume)

**Offline Experience:**
- **NFR-U7:** Offline mode must be detectable and indicated within 1 second of connection loss
- **NFR-U8:** Cached catalog must remain functional 100% offline (browse, search, sort cached data)
- **NFR-U9:** Network state changes must update UI within 2 seconds

**Permission Flow:**
- **NFR-U10:** Permission requests must be sequential (never request multiple simultaneously)
- **NFR-U11:** Each permission must have clear in-context explanation before request
- **NFR-U12:** App must function with graceful degradation if optional permissions denied

### Maintainability

**Code Quality:**
- **NFR-M1:** All coroutine operations must use `ensureActive()` for clean cancellation
- **NFR-M2:** StateFlow updates must be atomic to prevent race conditions
- **NFR-M3:** Diagnostic logs must capture sufficient context for remote troubleshooting

**Backward Compatibility:**
- **NFR-M4:** Migration from v2.4.0 in-memory queue to v2.5.0 Room queue must be automatic and lossless
- **NFR-M5:** Min SDK must remain API 29 to support Quest 1 devices

**Testing:**
- **NFR-M6:** All FRs must have corresponding automated tests (unit, integration, or UI)
- **NFR-M7:** WorkManager restart scenarios must have instrumented tests with process kill simulation

**Deployment:**
- **NFR-M8:** APK size must not exceed 50MB (sideloading constraint)
- **NFR-M9:** App updates must not break existing downloads in progress
