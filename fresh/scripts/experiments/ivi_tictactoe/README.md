# IVI / Tic-Tac-Toe — protocole de répétition

Scripts **à conserver dans Git avec le code source**. Le script `run_10.sh` ne modifie ni code Java, ni modèle B, ni configuration Maven. Les fichiers créés restent dans `experiments/ivi_tictactoe_once_<horodatage>_<identifiant>/` sous la racine du dépôt. Le script peut être appelé depuis n'importe quel répertoire.

## Pré-requis

- macOS/Linux, Bash, Python 3, Java 17, Maven, et dépendances Maven du projet.
- `pom.xml` déclare `de.prob2.kernel` version **4.16.1**.
- IVI doit prendre en charge `BFS_ACTIONS` et `-Drllib4prob.ivi.seed`.
- Fichiers `src/main/resources/TicTacToe/tictac.mch` et `rewards.def` validés par ProB.

## Exécuter

Depuis la racine du dépôt :

```bash
bash scripts/experiments/ivi_tictactoe/run_10.sh
```

Le script fixe `INCREMENTAL_VALUE_ITERATION ONCEANDFORALL PREPROCESS tictactoe`, `BFS_ACTIONS` et les graines **0..9**, dans dix processus Java distincts. Il lit les paramètres dans les journaux et contrôle `gamma=0.9`, `theta=0.01`, `maxIterations=100`, `updatesPerIteration=500`. Ces valeurs proviennent de la configuration chargée, et non de paramètres imposés à l'agent par le script. Toute différence est signalée dans `results.csv`.

## Sorties

- `logs/seed_N.log` : journal brut et code de sortie de la JVM.
- `results.csv` : toutes les graines, statuts, résidus, backups, temps, cache et anomalies détectées.
- `protocol.txt` : protocole, versions Java/Maven, OS, architecture, SHA du commit Git et indication de modifications locales suivies.
- `inputs/` et `input_sha256.txt` : copies et empreintes SHA-256 des principaux fichiers, pour examiner l'état exact des entrées.

Recalculer le CSV sans relancer les expériences :

```bash
python3 scripts/experiments/ivi_tictactoe/summarize.py experiments/NOM_DU_DOSSIER
```

Une exécution n'est comptée comme convergée que si le statut `CONVERGED`, le résidu, le code de sortie et les contrôles de cohérence sont corrects. Les runs au plafond d'itérations ne sont **pas** des convergences. Les statistiques de dispersion du script s'appliquent seulement aux runs convergés et contrôlés.

## Publication Git / reproductibilité scientifique

Conserver dans Git `scripts/experiments/ivi_tictactoe/`, `pom.xml`, les classes Java, les machines B et les fichiers de configuration. Publier aussi les données expérimentales effectivement utilisées par l'article (au minimum `results.csv`, `protocol.txt`, `input_sha256.txt` et les journaux bruts). Éviter d'ignorer globalement `experiments/` ou `*.log` si ces traces constituent les preuves publiées. En revanche, `target/` est généré et ne doit pas être suivi.

**Important :** la reproductibilité d'une graine fixe exige la même version du code, les mêmes entrées et un ordre canonique des états ; les durées murales restent sensibles à la machine et à sa charge. Les compteurs `rewardLookups` ne constituent pas une mesure des appels internes à ProB.

La première campagne utilisateur du 22 octobre 2026 (10/10 convergées) a été produite avant cette intégration Git. Elle doit être conservée avec ses journaux d'origine, **sans les régénérer ni inventer les données manquantes**.
