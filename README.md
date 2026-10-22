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

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shprogress` | `/shp` | Commande principale pour gérer le plugin SHProgress. | — |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shprogress.admin.progress` | Permet de faire progresser un joueur dans un objectif. | `op` |
| `shprogress.admin.reload` | Permet de recharger la configuration du plugin. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHProgress/
├── <a href="src/main/resources/default_tree.yml">default_tree.yml</a>
└── <a href="src/main/resources/documentation.yml">documentation.yml</a>
</pre>

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
