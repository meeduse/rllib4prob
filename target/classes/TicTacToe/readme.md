# Objectif

* L'objectif est d'apprendre à jouer au morpion.
* On considère deux joueurs 1 et 0 et on vous laisse le soin de voir comment réaliser l'apprentissage. Différentes stratégies sont possible : 

    - apprendre 1 avec 0 qui joue à chaque fois de manière aléatoire, 
    - apprendre 1 avec 0 qui joue à chaque fois la même chose,
    - apprendre 1 et 0, les deux étant aléatoires au début, mais chacun converge progressivement

* Une fois l'apprentissage fini, on souhaite que l'application se mette en mode interactif pour que l'utilisateur puisse jouer, en étant 0 ou 1. 


## Dans cette spécification :

* C'est toujours 0 qui joue en premier. On pourra modifier pour que ce soit aléatoire (0 ou 1) et disposer ainsi d'un apprentissage plus juste. Pour ce faire, changez la clause INITIALISATION en y mettant : trun :: (0..1) || square := {}. Cela aura un impact sur l'initialisation. Plutôt que d'avoir une initialisation possible vous en aurez deux. Il faudra donc redéfinir la méthode initialise de la classe Runner.java pour qu'elle en tire une de manière aléatoire. Discutez en avec votre enseignant. 

* L'état de la spécification est représenté par la variable square, c'est une fonction partielle qui affecte à des coordonnée la valeur 0 ou la valeur 1 : ((1..3) * (1..3)) +-> (0..1) 

* Dans TicTacToeRunner.java on vous donne une opération prettyPrintTicTacToe pour afficher la variable square sous une forme plus jolie. Vous avez la liberté sur ce code. Libérez votre imagination !

* On a définit une contrainte paramétrique win(p). L'évaluation de cette contrainte dans un état donné permet savoir si p (0 ou 1) a gagné ou pas.

### Exemple de sortie TicTacToeRunner.java

```
Load classical B Machine
Load success

   |   | 0
---+---+---
   |   |  
---+---+---
   |   |  

win(1) FALSE
win(0) FALSE

   | 1 | 0
---+---+---
   |   |  
---+---+---
   |   |  

win(1) FALSE
win(0) FALSE

   | 1 | 0
---+---+---
   |   | 0
---+---+---
   |   |  

win(1) FALSE
win(0) FALSE

   | 1 | 0
---+---+---
   | 1 | 0
---+---+---
   |   |  

win(1) FALSE
win(0) FALSE

   | 1 | 0
---+---+---
   | 1 | 0
---+---+---
   |   | 0

win(1) FALSE
win(0) TRUE