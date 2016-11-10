lazy val root = (project in file(".")).settings(
  name := "event-sourcing",
  version := "0.1.1",
  scalaVersion := "2.11.8",
  libraryDependencies ++= Seq(
    "com.typesafe.slick"  %% "slick"                    % Versions.slick,
    "com.typesafe.slick"  %% "slick-hikaricp"           % Versions.slick,
    "com.h2database"      %  "h2"                       % Versions.h2,
    "org.postgresql"      %  "postgresql"               % Versions.postgres,
    "com.typesafe.akka"   %% "akka-actor"               % Versions.akka,
    "com.typesafe.akka"   %% "akka-cluster"             % Versions.akka,
    "com.typesafe.akka"   %% "akka-cluster-sharding"    % Versions.akka,
    "com.typesafe.akka"   %% "akka-slf4j"               % Versions.akka,
    "com.typesafe.akka"   %% "akka-persistence"         % Versions.akka,
    "com.typesafe.akka"   %% "akka-contrib"             % Versions.akka,
    "com.typesafe.akka"   %% "akka-testkit"             % Versions.akka,
    "com.typesafe.akka"   %% "akka-multi-node-testkit"  % Versions.akka,
    "com.github.dnvriend" %% "akka-persistence-jdbc"    % "2.6.8",
    "org.scalactic"       %% "scalactic"                % Versions.scalatest,
    "org.scalatest"       %% "scalatest"                % Versions.scalatest  % "test"
  )
).enablePlugins(PlayScala)
