package com.example.journals

import javax.inject.Inject

class FireAndForgetInitializer @Inject() private[journals](initializable: Initializable) extends Initializer {
  initializable.init()
}
