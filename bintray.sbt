
bintrayOrganization in ThisBuild := Some("s22s")

//http://dl.bintray.com/s22s/maven-releases
publishArtifact in (Compile, packageDoc) in ThisBuild  := false

bintrayReleaseOnPublish in ThisBuild := false
