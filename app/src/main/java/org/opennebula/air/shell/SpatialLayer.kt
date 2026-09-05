package org.opennebula.air.shell

import java.util.concurrent.atomic.AtomicReference

class SpatialLayer(
    val id: String,
    val title: String,
    val panel: PanelFramebuffer,
    val pose: AtomicReference<SpatialWindow>,
)
