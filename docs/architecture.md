# Architecture du projet

Ce document résume comment la bibliothèque est organisée 

## Composants principaux

### `EnvironmentFactory`

Le point de routage central est [EnvironmentFactory.java](../src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java).

Il fait correspondre un nom d'environnement à une implémentation concrète:

| Nom demandé | Environnement Java | Machine B |
| --- | --- | --- |
| `autoV2` | `AutoV2Environment` | `/autoV2/HighwayPerception_Q.mch` |
| `Frozen_lake` / `frozenlake` | `FrozenLakeEnvironment` | `/frozen_lake/FrozenLakeGrid_4_4.mch` |
| `Interlocking` / `interlocking` | `InterlockingEnvironment` | `/interloking/interlocking.mch` |
| `puzzle` | `PuzzleEnvironment` | `/Puzzle/Puzzle8.mch` |
| `Taxi-driver` / `taxi_driver` / `taxi` | `TaxiDriverEnvironment` | `/taxi_driver/TaxiDriverGrid_5_5.mch` |
| `Moutain_Car` / `mountain_car` / `mountaincar` | `MountainCarEnvironment` | `/Mountain_Car/MountainCarSmall.mch` |
| `tictactoe` / `tic tac toe` / `tic_tac_toe` | `TicTacToeEnvironment` | `/TicTacToe/tictac.mch` |

Si le nom ne correspond à aucun cas, le factory retombe sur `TicTacToeEnvironment`.

### Les environnements

Chaque environnement encapsule:

1. la machine B à charger,
2. la logique spécifique d'initialisation,
3. la stratégie de récompense,
4. la manière d'interagir avec ProB.

Cela permet d'avoir un cœur commun tout en gardant des différences par domaine.

### `AgentParameterCatalog`

Le fichier [config/agent-parameters.properties](../config/agent-parameters.properties) contient les paramètres par défaut et les surcharges par environnement.

Le catalogue Java [AgentParameterCatalog](../src/main/java/fr/polytech/mnia/AgentParameterCatalog.java) lit d'abord ce fichier local, puis une ressource classpath si besoin. Ensuite il applique les règles de résolution:

1. `env.<env>.<algo>.<field>`
2. `env.<env>.default.<field>`
3. `default.<algo>.<field>`
4. `default.default.<field>`

Cette hiérarchie permet de spécialiser un paramètre sans dupliquer toute la configuration.

## Flux d'exécution

Un run suit généralement cet ordre:

1. le script ou la ligne de commande choisit l'environnement, l'algorithme, la récompense et l'exploration,
2. `EnvironmentFactory` construit l'environnement concret,
3. l'environnement charge la machine B correspondante,
4. `AgentParameterCatalog` fournit les hyperparamètres,
5. l'algorithme exécute exploration, apprentissage ou planification,
6. les métriques sont écrites dans les logs et les CSV de session.

## Architecture des ressources

Les fichiers `.mch` et ressources ProB sont stockés dans `src/main/resources/` et recopiés dans le classpath au build.

Exemples de dossiers:

1. `autoV2/`
2. `frozen_lake/`
3. `interlocing/`
4. `Puzzle/`
5. `taxi_driver/`
6. `Mountain_Car/`
7. `TicTacToe/`

Cette organisation est importante: les chemins codés dans `EnvironmentFactory` doivent correspondre aux ressources réellement disponibles.

## Points d'attention

### Défaut de routage

Le cas `default` renvoie vers `TicTacToeEnvironment`. C'est pratique pour éviter un crash, mais cela peut masquer une faute de frappe dans le nom de l'environnement.

### Cohérence des chemins

Le nom logique de l'environnement et le chemin de la machine B doivent rester alignés. Si on change un dossier de ressources, il faut aussi mettre à jour `EnvironmentFactory`.

## Résumé

Le projet repose sur une architecture simple:

1. un factory pour choisir l'environnement,
2. des classes d'environnement pour encapsuler ProB,
3. un catalogue de paramètres pour injecter les bons hyperparamètres,
4. des scripts pour lancer et collecter les résultats.

