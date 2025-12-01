# Objectif

Dans ce dossier on vous fournit deux modèles qui tournent autour du problème des bandits manchots. On veut que vous implémentez les algorithmes vus en cours, avec un accent particulier sur l'algorithme bandits gradients. 

## Spécifications fournies

- La spécification SimpleRL.mch correspond à l'exemple qu'on a vu en TD. On dipose de 3 films A, B et C et d'un utilisateur avec un comportement déterministe. Il aime A et B, et pas C. L'objectif est donc de tester vos algorithmes, **ε-Greedy** (moyennes pondérées et/ou incrémentales), **UCB (Upper Confidence Bound)** et **Bandit Gradient** et de comparer leur convergence. On sait qu'à la fin on devrait avoir une forte valeur pour A et B (proche de 100%), et une faible valeur pour C (proche de 0%).

- La spécification YouTube.mch, modélise un comportement non déterministe avec des catégories de flux vidéos : tuto de Python, voyages, musique populaire et jeux videos. Cette fois-ci on considère que l'information utile à la récompense est la durée de visionnage (modélisée par duration). Par exemple, duration = 25 veut dire que l'utilisateur a regardé 25% de la video montrant ainsi un certain intérêt (plutôt faible) à la vidéo. Un autre point intéressant ici est la variation des choix de l'utilisateur. Par exemple, au début de l’entraînement l'utilisateur préfère la musique aux tutos python mais ensuite cette tendance s'inverse. Par contre l'utilisateur est toujours intéressé par les jeux videos.

## Notes utiles

L'algorithme des **bandits gradient** est un excellent choix pour des systèmes de **recommandation adaptatifs**, comme ceux utilisés par **YouTube, Netflix ou TikTok**. Ces plateformes doivent choisir **quelle vidéo recommander** à un utilisateur pour **maximiser son engagement**.

Lorsque un utilisateur ouvre YouTube, l’algorithme doit choisir **quelle vidéo afficher en premier** sur sa page d’accueil. Ce choix est basé sur un **compromis exploration/exploitation** :
- Faut-il recommander une **vidéo déjà populaire** (exploitation) ?
- Faut-il tester une **nouvelle vidéo** qui pourrait plaire (exploration) ?

Le problème peut être modélisé comme un **bandit manchot** où chaque **vidéo est un bras** du bandit.

| Élément | Correspondance dans notre exemple |
|---------|-----------------------------------|
| **Environnement** | YouTube (l'application qui affiche des vidéos et collecte les interactions) |
| **Agent** | L'algorithme de recommandation |
| **Actions** | Choisir une vidéo à recommander |
| **Récompense** | Engagement utilisateur : clic, durée de visionnage, like, etc. |
| **Politique** | Distribution softmax des probabilités d’affichage des vidéos |

L’algorithme doit apprendre une **politique de sélection** qui maximise l’engagement.

## Comparaison avec d'autres méthodes

| Algorithme | Stratégie | Avantages | Inconvénients |
|------------|-----------|------------|---------------|
| **ε-Greedy** | Exploite le meilleur bras (1 - ε) du temps, explore avec ( ε ) | Simple, efficace | Exploration aléatoire |
| **UCB (Upper Confidence Bound)** | Sélectionne le bras avec la meilleure confiance | Réduit l'exploration inutile | Moins efficace sur de grands espaces |
| **Bandit Gradient** | Ajuste dynamiquement la probabilité de sélection avec softmax | Bonne adaptation aux préférences | Plus complexe à implémenter |




