package com.example.io

trait CanWrite[Model, Rep] {
  def write(a: Model): Rep
}
