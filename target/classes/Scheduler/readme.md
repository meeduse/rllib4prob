# Objectif

* L'agent doit apprendre à minimiser le temps d'attente des processus tout en maximisant l'utilisation du CPU et tout en gardant un temps de latence acceptable. Le CPU ne doit pas rester inactif si des processus sont prêts à être activés. On considère qu'un temps de latence est acceptable s'il ne dépasse pas considérablement un certain seuil.

* L'ordonnancement des processus sera conclu quand tous les processus se soient exécutés au moins une fois. Il faut donner dans ce cas, la séquence optimale selon votre stratégie.

* scheduler.mch : contient l'ordonnanceur

* scheduler_main.mch : utilise l'ordonnanceur en introduisant les notions de temps d'attente, de latence et d'exécution.

## Dans cette spécification :

* Un processus peut dépasser son temps d'exécution. Dans ce cas, il bloque le CPU car on ne peut pas exécuter free pour le libérer. Son arrêt doit être forcé par l'arrivé d'un autre processus permettant l'enclenchement de swap suivi de delete. C'est typiquement une situation qu'on devrait éviter. On doit libérer un processus dès qu'il termine (execution_time = 0). L'agent doit apprendre à éviter ce genre de comportement.

* On peut interrompre l'exécution d'un processus à tout moment pour prioriser l'exécution d'un processus prêt.  Ceci se fait avec swap. Dans ce cas, le processus interrompu se met en mode waiting et attend qu'il soit réactivé de nouveau (avec l'appel à activate suivi de swap).  

* Le temps de latence correspond au temps où un processus n'est pas encore introduit dans le scheduler. Ce temps est remis à zéro quand on libère un processus (opération free) après qu'il ait finit son exécution. Si le processus est introduit dans le système avec start et ensuite libéré avec delete sans qu'il ait été exécuté, alors son temps de latence continue de tourner. On considère qu'un temps de latence est acceptable s'il ne dépasse pas trop un certain seuil (par exemple, 10 unités de temps).

* Les actions réalisées entre deux appels à step sont considérées comme étant instantanées. Cependant, il est important de minimiser les opérations superflues. Par exemple, une boucle (new ; delete)* sur le même processus n'a aucun intérêt.

* Le temps d'attente n'est pas réinitialisé quand le processus est libéré. Il s'agit d'un temps cumulatif. Contrairement au temps de latence qui est remis à zéro quand un processus est libéré. 