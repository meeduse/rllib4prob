# Campagnes offline généralisées — RLlib4ProB

La configuration d'une campagne est le fichier JSON `<identifiant>.json` dans ce répertoire. Le gestionnaire `../manage_campaigns.py` est identique pour tous les environnements.

## Installation dans `fresh`

Depuis le répertoire `fresh`, extraire `generic_campaign_bundle.zip` dans le projet :

```bash
cd /Users/idani/rllib4prob/fresh
unzip -o ~/Downloads/generic_campaign_bundle.zip
```

Le paquet **ne modifie pas** `run_campaign.sh`, `run_campaign.py`, les sources Java, les machines B ou le fichier de paramètres. Il ne remplace que les nouveaux fichiers `manage_campaigns.py`, `campaign.sh`, `campaigns/*.json` et ses tests.

## Utilisation par identifiant

```bash
bash scripts/experiments/offline/campaign.sh taxidriver --plan
bash scripts/experiments/offline/campaign.sh taxidriver --validate
bash scripts/experiments/offline/campaign.sh taxidriver --run
```

Ou, pour les deux phases enchaînées **uniquement en cas de validation intégralement réussie** :

```bash
bash scripts/experiments/offline/campaign.sh taxidriver --all
```

Afficher les campagnes finales déjà présentes :

```bash
bash scripts/experiments/offline/campaign.sh taxidriver --status
```

Le gestionnaire retrouve le projet en remontant depuis son emplacement ; inutile de changer de dossier pour l'invoquer.

## Manifeste d'une nouvelle campagne

Créer `scripts/experiments/offline/campaigns/monbenchmark.json` :

```json
{
  "id": "monbenchmark",
  "algorithms": ["VI", "PI", "MPI", "IVI", "PVI", "BI"],
  "explorations": ["PREPROCESS", "RECURSIVE"],
  "repetitions": 10,
  "gamma": 0.9,
  "theta": 0.01,
  "biHorizon": 150,
  "variants": [
    {
      "id": "small",
      "expectedStates": 43,
      "environments": {
        "ONCEANDFORALL": "monbenchmark",
        "EMBEDDED": "monbenchmarkembedded"
      }
    }
  ]
}
```

Les noms d'environnement doivent déjà être reconnus dans `EnvironmentFactory.java`. Les paramètres effectifs `gamma`, `theta` et `horizon` doivent être définis dans `config/agent-parameters.properties` et doivent correspondre au manifeste. Le champ `expectedStates` contient **les états applicatifs, hors nœuds administratifs ProB**, confirmés préalablement par un test d'exploration.

## Contrôles

Pour chaque configuration, `--validate` exécute **une répétition** via le lanceur existant ; le statut de succès n'est pas déduit du seul code de sortie. Le gestionnaire relit `results.csv` et `summary.json` et contrôle notamment :

- Identité algorithme/environnement/récompense/exploration, états attendus et code retour.
- `VERIFIED_SUCCESS` pour chaque exécution.
- VI/PI/MPI/IVI/PVI : `CONVERGED`, résidu global fini et inférieur ou égal à `theta`, gamma et theta attendus ; `policyStable=true` pour PI/MPI.
- BI : `HORIZON_COMPLETED`, horizon intégral atteint et delta fini ; **pas** assimilé à une convergence de planification à horizon infini.
- Once-and-for-all : souscription/cache avec `cacheMisses = cachedStates = expectedStates` et `cacheHits + cacheMisses = rewardLookups`.
- Campagnes finales : exactement `repetitions` lignes, indices 0..N−1, graines IVI 0..N−1, contrôles sur toutes les répétitions.
- Validation enregistrée, empreinte SHA-256 des sources et du manifeste, et compatibilité du snapshot `input_sha256.txt` de chaque campagne finale.

Si une validation échoue, `--run` est interdit. Si un résultat final est incomplet ou ambigu, l'exécution s'arrête **sans déplacer aucun dossier**. Il faut examiner l'anomalie, l'archiver explicitement puis reprendre.

## Sorties et archivage

- `experiments/campaigns/<id>/validation-<date>/validation.csv` : une ligne par configuration, résultats de validation et chemins des journaux.
- `experiments/campaigns/<id>/validation-ok.json` : autorisation de lancer les répétitions finales, invalide si le code ou les paramètres changent.
- `<id>_<variant>_final_results.zip` à la racine de `fresh`, avec **uniquement** les `results.csv` et `summary.json` finaux de chaque configuration (pour audits ultérieurs).
- `experiments/offline/...` : campagnes finales et leurs logs/snapshots conservés intacts.
- `~/rllib4prob-private-archive/<id>-pilots-<date>/` : les campagnes de validation à une répétition, déplacées **uniquement quand toutes les campagnes finales sont vérifiées et exportées**.

Ne pas supprimer les anciennes données à la main pour faire disparaître des échecs ; les conserver à part si un résultat nécessite une correction. Les manifests Frozen Lake fournis permettent de **reproduire de nouvelles campagnes**, pas d'importer automatiquement les campagnes terminées antérieurement.

## Tests isolés

Le gestionnaire inclut des tests avec lanceur simulé, sans ProB :

```bash
python3 scripts/experiments/offline/tests/test_manage_campaigns.py -v
```

Les tests vérifient le blocage sans validation préalable, la chaîne complète validation→run→ZIP→archivage, la reprise de campagnes finales déjà validées, le blocage après changement des modèles, et l'arrêt en cas de campagne finale corrompue.
