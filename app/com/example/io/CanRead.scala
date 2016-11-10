package com.example.io

trait CanRead[Model, Rep] {
  def read(a: Rep): Model
}
