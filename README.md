<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHProgress

*Un plugin pour créer des arbres de progrès personnalisés.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Un plugin pour créer des arbres de progrès personnalisés.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `UltimateAdvancementAPI`
- Optionnelle : `SHNetwork` (progression partagée entre plusieurs serveurs Opale, voir plus bas)

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shprogress progress <joueur> <arbre/progrès> <quantité>` | `/shp` | Fait progresser un joueur connecté (utilisé par SHCrates). | `shprogress.admin.progress` |
| `/shprogress reload` | `/shp` | Recharge `config.yml`, `messages.yml` et les arbres. | `shprogress.admin.reload` |
| `/shprogress import` | `/shp` | Avec SHNetwork : copie les fichiers `playerdata/` dans la base du réseau. | `shprogress.admin.import` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shprogress.admin.progress` | Permet de faire progresser un joueur dans un objectif. | `op` |
| `shprogress.admin.reload` | Permet de recharger la configuration du plugin. | `op` |
| `shprogress.admin.import` | Permet de copier la progression des fichiers de ce serveur dans la base du réseau. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHProgress/
├── <a href="src/main/resources/config.yml">config.yml</a>
├── <a href="src/main/resources/messages.yml">messages.yml</a>
├── <a href="src/main/resources/default_tree.yml">default_tree.yml</a>
├── <a href="src/main/resources/documentation.yml">documentation.yml</a>
├── trees/
└── playerdata/
</pre>

La progression de chaque joueur est dans `playerdata/<uuid>.yml` (`progress:` puis un compteur par progrès, plafonné à sa quantité), écrite toutes les 5 minutes et à la déconnexion.

## Plusieurs serveurs Opale (SHNetwork)

Avec SHNetwork et `reseau.actif: true`, la progression passe dans la base du réseau : un joueur retrouve ses compteurs en changeant de serveur et la récompense d'un progrès n'est donnée qu'une fois pour tout le réseau. Sans SHNetwork, rien de ce qui suit n'est chargé : le plugin garde ses fichiers `playerdata/` et son fonctionnement d'origine.

- **Arrivée d'un joueur** : le serveur attend que celui qu'il quitte ait fini d'écrire sa progression (`reseau.attente-depart-secondes`), puis la lit en base sans bloquer le fil principal. Tant qu'elle n'est pas lue, rien ne compte pour lui (environ une seconde).
- **Compteurs** : les gains sont gardés en mémoire pendant la partie, puis ajoutés à la base par `progression = LEAST(progression + gain, quantité)` toutes les `reseau.sauvegarde-secondes` secondes et au départ du joueur. Une valeur écrite entre-temps par un autre serveur n'est jamais écrasée.
- **Récompenses** : la ligne `(joueur, progrès)` de `shprogress_rewards` est créée par `INSERT IGNORE` au moment où le progrès se termine. Le serveur qui la crée donne les commandes et les messages, les autres ne donnent rien.
- **Reprise des fichiers** : `/shprogress import` copie `playerdata/` dans la base sans toucher aux progressions déjà présentes et marque les progrès terminés comme déjà récompensés. Elle peut être relancée sans rien compter deux fois.

Tables de la base `bo_net` (créées au démarrage, ou à la main avec [`sql/schema.sql`](sql/schema.sql)) :

| Table | Colonnes | Rôle |
|---|---|---|
| `shprogress_progress` | `uuid`, `advancement`, `progress` | Compteur de chaque progrès (clé : joueur + `arbre/progrès`) |
| `shprogress_rewards` | `uuid`, `advancement`, `claimed_at` | Récompenses déjà données (clé : joueur + `arbre/progrès`) |

Clés de `config.yml` sous `reseau:`

| Clé | Rôle |
|---|---|
| `actif` | Active le mode réseau quand SHNetwork est présent (redémarrage) |
| `attente-depart-secondes` | Attente maximale de l'écriture du serveur de départ |
| `sauvegarde-secondes` | Intervalle entre deux écritures des gains (redémarrage) |
| `base-de-donnees.url`, `utilisateur`, `mot-de-passe`, `delai-ms` | Accès à MariaDB (redémarrage) |

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
