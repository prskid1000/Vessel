cmake_minimum_required(VERSION 3.22.1)

project(vessel_native C)

# Only libwinlator. Upstream also builds vortekrenderer, virglrenderer,
# gladiorenderer, midihandler and libadrenotools from this file; none of them
# are Vessel's display path. See app/src/main/java/com/winlator/README.md.
add_subdirectory(winlator)
