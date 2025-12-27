# Plan d'Action - Rookie On Quest

Ce document décrit le plan d'action basé sur les issues GitHub actuelles.

## 1. Priorité Critique (Bugs)

### [BUG] Nothing happens after download. (#5)
*   **Objectif :** Diagnostiquer et corriger le problème empêchant l'installation ou l'action post-téléchargement.
*   **Actions :**
    *   Vérifier les logs lors de la complétion du téléchargement.
    *   Examiner le `BroadcastReceiver` ou le callback du gestionnaire de téléchargement.
    *   S'assurer que les permissions d'installation d'APK sont correctement gérées.

## 2. Gestionnaire de Téléchargement (Download Manager Refactor)

Ce groupe de fonctionnalités nécessite probablement une refonte de la logique de téléchargement pour gérer l'état et la persistance des fichiers.

### [FEATURE] Resume download (#8)
*   **Objectif :** Permettre la reprise d'un téléchargement interrompu.
*   **Actions :** Utiliser les capacités de reprise du `DownloadManager` Android ou implémenter une gestion manuelle des `Range` headers.

### [FEATURE] Keep downloaded file (#7)
*   **Objectif :** Ne pas supprimer le fichier APK après l'installation (ou l'échec).
*   **Actions :** Ajouter une option dans les paramètres (à créer si inexistant) pour conserver les fichiers. Modifier le nettoyage post-installation.

### [FEATURE] Option to download only and install later (#9)
*   **Objectif :** Dissocier le téléchargement de l'installation immédiate.
*   **Actions :** Ajouter un bouton "Télécharger" distinct de "Installer" ou une option contextuelle.

## 3. Interface Utilisateur & Données

### [FEATURE] Add other fields to the games list (#6)
*   **Objectif :** Afficher plus d'informations dans la liste des jeux (ex: version, taille, date).
*   **Actions :**
    *   Mettre à jour le modèle de données (`GameData` ou équivalent).
    *   Mettre à jour le parsing JSON si nécessaire.
    *   Adapter le layout de l'élément de liste (`GameListItem`).

### [FEATURE] Update popup (#10)
*   **Objectif :** Avertir l'utilisateur lorsqu'une nouvelle version de l'application est disponible.
*   **Actions :**
    *   Implémenter une vérification de version au lancement (appel API GitHub Releases ou fichier JSON distant).
    *   Afficher une `AlertDialog` ou un `Snackbar` si une mise à jour est détectée.
