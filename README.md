# BastionMCMines — BASTION MC (Purpur 1.21.11)

Proyecto Java 21, Maven. Ejecuta `mvn clean package` o sube el contenido a GitHub y descarga el JAR desde **Actions → Build BastionMCMines → Artifacts**.

**Instalación:** coloca el JAR en `plugins/`, reinicia, configura `/mina` como OP. PlaceholderAPI es opcional para los hologramas. WorldGuard y LuckPerms pueden convivir con el plugin; las minas privadas emplean permisos Bukkit que LuckPerms puede otorgar. 

**Crear:** `/mina crear minapvp public` o `/mina crear minavip private`. Con hacha dorada: clic izquierdo primer punto, clic derecho para añadir puntos. Luego `/mina finish`. Define el spawn fuera del área con `/mina setspawn minapvp`. Cada mina arranca con 20 bloques de profundidad y reinicio cada 60 segundos.

**Permiso de privadas:** `bastionminas.acceso.minavip`. Ejemplo: `/lp group vip permission set bastionminas.acceso.minavip true`.

**Administración OP:** `/mina menu` o `/minas menu`, `/mina reset <mina>`, `/mina listar`, `/mina info <mina>`, `/mina eliminar <mina>`, `/mina profundidad <mina> <bloques>`, `/mina setspawn <mina>`, `/mina reload`, `/minareload`.

**GUI:** al abrir Bloques, haz clic en un bloque de tu inventario (inferior) para añadirlo. Haz clic en un bloque configurado para editar su porcentaje escribiéndolo en el chat; clic derecho elimina el bloque (requiere mantener al menos uno). En Recompensas, selecciona un bloque, luego haz clic en un ítem de tu inventario para copiarlo como recompensa. Clic izquierdo sobre recompensa cambia probabilidad; clic derecho elimina. El panel 'Drops originales' alterna su reemplazo. Cada recompensa realiza una tirada independiente y cae al suelo. El anuncio global aplica a probabilidades <= 0.01% por defecto. En Brillo, clic izquierdo sobre bloque abre colores; clic derecho apaga. El brillo usa partículas visibles sobre caras expuestas, **no el contorno Glowing a través de paredes**.

**Temporizadores:** ajuste desde GUI vía chat (minutos). BossBar sólo al faltar 5 minutos o menos, solo para jugadores dentro. Reinicio: teletransporte primero, luego regeneración en lotes para reducir picos. Anuncio global del reinicio es individual por mina y DESACTIVADO inicialmente; actívalo en minapvp desde Anuncios.

**Placeholders PAPI:** `%BastionMinas_tiempo_reinicio_minapvp%`, `%BastionMinas_nombre_minapvp%`, `%BastionMinas_jugadores_minapvp%`, `%BastionMinas_bloques_restantes_minapvp%`, `%BastionMinas_porcentaje_minapvp%`, `%BastionMinas_estado_minapvp%`. FancyHolograms necesita actualizar líneas con placeholders.

**Notas de implementación:** La regeneración reemplaza TODO el volumen de la mina. Respaldar el mundo antes de crear la primera mina. La GUI utiliza slots convencionales y nombres configurables, mientras algunos textos de botones siguen definidos en código en esta primera versión. La integración completa de permisos de construcción con regiones de WorldGuard superpuestas depende de sus prioridades/flags y debe probarse antes de abrir al público. Los materiales admitidos para el relleno son bloques Bukkit normales (no CustomBlockData de terceros). Esta entrega contiene código fuente y workflow; la compilación local no ha sido verificada en este entorno.
