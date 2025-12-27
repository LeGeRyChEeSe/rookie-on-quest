# Plan d'Action - Rookie On Quest

Ce document décrit le plan d'action basé sur les issues GitHub actuelles.

## 1. Terminé ✅

### [BUG] Nothing happens after download. (#5)
*   **Statut :** Résolu. L'installation se lance via `FileProvider`.

### [FEATURE] Resume download (#8)
*   **Statut :** Résolu. Support des headers HTTP `Range`.

### [FEATURE] Alphabetical Indexer
*   **Statut :** Résolu. Navigation rapide fonctionnelle.

### [FEATURE] Display game size in list (#6) - Base & Cache
*   **Statut :** Résolu. Affichage de la taille et mise en cache locale (SharedPreferences).

## 2. En cours / Priorité Immédiate 🚀

### [UX] Prioritized size fetching
*   **Objectif :** Charger les tailles des jeux visibles ou recherchés en priorité.
*   **Actions :**
    *   Passer les indices visibles de la `LazyColumn` au `ViewModel`.
    *   Réorganiser la file d'attente `fetchGameSizes` dynamiquement.

### [FEATURE] Update popup (#10)
*   **Objectif :** Vérification de version de l'app via GitHub API.

## 3. Gestionnaire de Téléchargement (Refactor)
*   **[FEATURE] Keep downloaded file (#7) & Download only (#9)**

## 4. UX & Divers
*   **Multi-mirror support.**
