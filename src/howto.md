Voici un petit tutoriel que tu peux conserver dans `fresh`. L'objectif est simple : ajouter une nouvelle spécification B, déclarer une campagne, puis laisser le gestionnaire automatiser les validations et les expérimentations.

# Tutoriel — Ajouter un benchmark à RLlib4ProB

## 1. Organisation du projet

Supposons que tu ajoutes un environnement appelé `warehouse`.

Voici l'organisation recommandée :

fresh/

Spécifications B

&#x20;`src/main/resources/warehouse/`

&#x20;`Warehouse.mch`

&#x20;`WarehouseBis.mch`

&#x20;`rewards.def`

Adaptateur Java

&#x20;`src/main/java/fr/polytech/mnia/Environment/WarehouseEnvironment.java`

Déclaration de campagne

&#x20;`scripts/experiments/offline/campaigns/warehouse.json`

Paramètres communs

&#x20;`config/agent-parameters.properties`

## 2. Ajouter les machines B

Dans `src/main/resources/warehouse/`, place :

- `Warehouse.mch` : machine utilisée par Once-and-for-all.
- `WarehouseBis.mch` : machine utilisée par Embedded.
- `rewards.def` : définitions communes des récompenses.

Les deux machines doivent représenter le même environnement. Pour Once-and-for-all, la récompense doit pouvoir être évaluée dans l'état successeur ; pour Embedded, elle doit être renvoyée par l'opération B après la transition.

Avant de lancer une campagne, vérifie l'équivalence des graphes et des récompenses, comme nous l'avons fait pour Taxi Driver et Frozen Lake.

## 3. Déclarer l'environnement dans Java

Dans `EnvironmentFactory.java`, ajoute deux entrées :

```
case "warehouse" ->
    new WarehouseEnvironment(
        "/warehouse/Warehouse.mch", rewardStrategy);

case "warehouseembedded" ->
    new WarehouseEnvironment(
        "/warehouse/WarehouseBis.mch", rewardStrategy);
```

La classe `WarehouseEnvironment` doit être créée en adaptant une classe d'environnement existante et validée, notamment pour la souscription à `reward` et le cache Once-and-for-all.

Attention : hériter simplement de `ModelBasedEnvironment` ne garantit pas cette souscription. Le gestionnaire de campagne ne crée pas automatiquement cette classe Java.

## 4. Déclarer la campagne JSON

Crée le fichier :

`scripts/experiments/offline/campaigns/warehouse.json`

Avec ce contenu :

```
{
  "id": "warehouse",
  "description": "Warehouse model-based reinforcement learning",
  "algorithms": ["VI", "PI", "MPI", "IVI", "PVI", "BI"],
  "explorations": ["PREPROCESS", "RECURSIVE"],
  "repetitions": 10,
  "gamma": 0.9,
  "theta": 0.01,
  "biHorizon": 150,
  "variants": [
    {
      "id": "default",
      "expectedStates": 100,
      "environments": {
        "ONCEANDFORALL": "warehouse",
        "EMBEDDED": "warehouseembedded"
      }
    }
  ]
}
```

Ici, `expectedStates: 100` est uniquement un exemple : remplace 100 par le nombre réel d'états applicatifs explorés par ProB, sans les nœuds administratifs.

Si tu ajoutes plusieurs tailles, comme pour Taxi Driver, tu ajouteras simplement plusieurs entrées dans `variants`, sans changer le gestionnaire.

## 5. Configurer les paramètres

Dans `config/agent-parameters.properties`, ajoute au minimum les horizons BI :

```
env.warehouse.BACKWARD_INDUCTION.offline.horizon=150
env.warehouseembedded.BACKWARD_INDUCTION.offline.horizon=150
```

Les autres paramètres peuvent utiliser les valeurs par défaut existantes (`gamma=0.9`, `theta=0.01`, etc.). Tu peux les spécialiser par environnement ou par algorithme. Vérifie qu'ils correspondent aux paramètres déclarés dans le JSON.

Compile ensuite :

```
cd /Users/idani/rllib4prob/fresh
mvn -q -DskipTests test-compile
```

## 6. Exécuter la campagne

Commandes quotidiennes

warehouse

Afficher la matrice des configurations, sans lancer ProB

```
bash scripts/experiments/offline/campaign.sh warehouse --plan
```

Phase 1 — Tester chaque configuration une fois

```
bash scripts/experiments/offline/campaign.sh warehouse --validate
```

Phase 2 — Exécuter dix répétitions par configuration (si validation réussie)

```
bash scripts/experiments/offline/campaign.sh warehouse --run
```

Ou exécuter les deux phases automatiquement

```
bash scripts/experiments/offline/campaign.sh warehouse --all
```

Le moteur contrôle la convergence, les résidus, les backups, le cache Once-and-for-all, les graines IVI et les horizons BI. Il bloque la campagne finale en cas d'échec de validation ou de modification des sources depuis la validation.

## 7. Où retrouver les résultats ?

| Fichier ou dossier                    | Contenu                                                         |
| ------------------------------------- | --------------------------------------------------------------- |
| `experiments/campaigns/warehouse/`    | Rapports de validation                                          |
| `experiments/offline/`                | Campagnes et journaux complets                                  |
| `warehouse_default_final_results.zip` | Résultats finaux à transmettre pour l'audit                     |
| `~/rllib4prob-private-archive/`       | Essais préliminaires archivés après succès de toute la campagne |

À noter : le gestionnaire automatise actuellement les campagnes expérimentales, pas la création des machines B, des adaptateurs Java ou des tests d'équivalence.

En résumé : pour ajouter un nouvel environnement, tu modifies seulement les fichiers spécifiques au benchmark (B, Java, propriétés et JSON). Le moteur expérimental reste inchangé.

C'est le principe que je te recommande de conserver pour les prochains benchmarks, notamment Mountain Car, AutoV2, Interlocking et Puzzle.