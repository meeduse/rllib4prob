# Guide d'utilisation du runner d'expériences

Ce guide décrit le script [experimental_run.sh](experimental_run.sh) qui lance des expériences en parallèle, avec plusieurs répétitions par configuration, et stocke les sorties dans une arborescence organisée.

## Objectif

Le script sert à:

1. lancer plusieurs environnements dans une même session,
2. exécuter plusieurs répétitions par combinaison algorithme / récompense / exploration / environnement,
3. limiter le nombre de jobs simultanés,
4. garder les métriques, et éventuellement les logs bruts,
5. ranger tous les résultats dans un dossier horodaté.


## Lancement rapide

Le script compile une seule fois avant les runs, puis lance les expériences en parallèle.

Exemple minimal:

```bash
./experimental_run.sh --runs 1 --max-jobs 1 --no-logs
```

Exemple plus rapide sur CPU multi-cœurs:

```bash
./experimental_run.sh --runs 1 --max-jobs "$(nproc)" --no-logs
```

## Options disponibles

### `--runs N`
Nombre de répétitions par configuration.

Exemple:

```bash
./experimental_run.sh --runs 3
```

### `--max-jobs N`
Nombre maximal de jobs lancés en parallèle.

Exemple:

```bash
./experimental_run.sh --max-jobs 4
```

### `--output-root D`
Dossier racine où seront créés les résultats de session.

Exemple:

```bash
./experimental_run.sh --output-root results
```

### `--logs`
Conserve les logs bruts de chaque run. C'est le mode par défaut.

### `--no-logs`
N'écrit pas les logs bruts finaux, seulement les métriques agrégées.

Exemple:

```bash
./experimental_run.sh --no-logs
```

### `--envs LIST`
Permet de cibler un sous-ensemble d'environnements, séparés par des virgules.

Exemple:

```bash
./experimental_run.sh --envs autoV2,Interlocking
```

### `--help`
Affiche l'aide du script.

## Structure des sorties

Chaque lancement crée un dossier de session horodaté dans `experiments/` par défaut.

Exemple:

```text
experiments/20260708_153000/
├── results.csv
├── session.txt
├── runs/
│   └── <env>/<algo>/<reward>/<exploration>/run_<id>/
│       ├── metrics.csv
│       └── stdout.log
└── logs/
    └── <env>/<algo>/<reward>/<exploration>/run_<id>.log
```

Contenu important:

1. `results.csv` regroupe toutes les métriques de la session,
2. `session.txt` contient le résumé de configuration,
3. `runs/` contient un sous-dossier par exécution,
4. `logs/` contient les logs bruts seulement si `--logs` est actif.

## Colonnes exportées

Le CSV final contient:

1. `algorithm`
2. `reward`
3. `exploration`
4. `environment`
5. `run`
6. `states`
7. `t_explore_sec`
8. `t_learn_sec`
9. `iterations_or_updates`
10. `status`
11. `exit_code`
12. `log_file`

## Environnements et configurations

Le script appelle une configuration adaptée dans `config/agent-parameters.properties` via le code Java.

En pratique, les clés sont sélectionnées selon le nom de l'environnement passé au script, par exemple:

- `autoV2`
- `Frozen_lake`
- `Interlocking`
- `Taxi-driver`
- `Moutain_Car`

## Conseils d'usage

1. Pour tester la chaîne complète, commencez avec `--runs 1 --max-jobs 1`.
2. Pour une campagne réelle, augmentez `--max-jobs` au niveau de tes cœurs CPU.
3. Gardez `--logs` si vous souhaitez analyser les erreurs ou comparer les traces.
4. Utilisez `--no-logs` si vous souhaitez  surtout économiser de l'espace disque.

## Dépannage

### `StateSpace` nul ou machine non initialisée

Souvent, cela veut dire que ProB n'a pas pu ouvrir la machine correctement. Vérifie:

1. que le fichier `.mch` existe bien pour l'environnement,
2. que la configuration de l'environnement est correcte,
3. que ProB peut être lancé hors du script.

### Pas assez de parallélisme

Si les runs se lancent trop lentement, augmente `--max-jobs` progressivement jusqu'à un niveau stable.

## Exemple

```bash
./experimental_run.sh --runs 1 --max-jobs 4 --logs --envs autoV2,Interlocking
```

Cette commande:

1. lance 1 répétition par configuration,
2. garde 4 exécutions simultanées,
3. conserve les logs,
4. cible seulement deux environnements.