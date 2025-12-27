# Plan d'Action - Rookie On Quest

Ce document décrit le plan d'action basé sur les issues GitHub actuelles.

## 1. Terminé ✅

### [BUG] Nothing happens after download. (#5)
*   **Statut :** Résolu. L'installation se lance via `FileProvider`.

### [FEATURE] Resume download (#8)
*   **Statut :** Résolu. Support des headers HTTP `Range`.

### [FEATURE] Alphabetical Indexer
*   **Statut :** Résolu. Navigation rapide fonctionnelle.

### [FEATURE] Display game size in list (#6)
*   **Statut :** Résolu. Affichage de la taille, mise en cache via **Room Database** et chargement prioritaire (visible/recherche).

## 2. En cours / Priorité Immédiate 🚀

### [FEATURE] Update popup (#10)
*   **Objectif :** Vérification de version de l'app via GitHub API et affichage d'une popup de mise à jour.
*   **Actions :**
    *   Créer un service pour interroger l'API GitHub.
    *   Comparer la version locale avec la version distante.
    *   Afficher une boîte de dialogue si une mise à jour est disponible.

## 3. Gestionnaire de Téléchargement (Refactor)
*   **[FEATURE] Keep downloaded file (#7) & Download only (#9)**

## 4. UX & Divers
*   **Multi-mirror support.**
