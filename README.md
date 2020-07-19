# Krate

Unopiniated and simple ORM for Kotlin using Exposed

## Concept

Krate works using two base models: `Entity` and `Repository`. As the names imply, repositories do the job of managing CRUD operations regarding entities.

### Entities

Entities, extending the `Entity` abstract class, are objects that are uniquely identified by their `uuid` property. Entities can posess any number of properties, including properties that link them to other entities; this is achieved using bindings.

### Bindings

A binding makes a link (called a relationship) between a property of an entity class and a way of representing that relationship with SQL.

There currently exists five kinds of bindings supported by Krate, all declared in the `SqlBinding` sealed class :

- `SqlBinding.Value` - binds a property of an Entity class to a plain value column (in other words, a column containing a simple value without linking to other entities)
- `SqlBinding.OneToOne` - binds a property of type `Entity` of an the Entity class to a UUID column storing a reference to an entity of that type 
- `SqlBinding.OneToOneOrNone` - same, but accepts `null`
- `SqlBinding.OneToMany` -  binds a property of type `Collection<Entity>` of an the Entity classs to another table storing entities.
    - **note:** this binding kind represents a true one-to-many relationship; entities on the right-hand side of the relationship can only be linked to a single entity on the left-hand side, and this is enforced by Krate by requiring the entity table on the right-hand side to possess a FK to the entity table on the left-hand side.
- `SqlBinding.OneToManyValues` -  binds a property of an Entity class to a table storing plain values

*One-to-many relationships are not yet supported, but planned for the very near future*

### Polymorphism support

Krate supports storing entities in polymorphic hierarchies using `PolymorphicEntityTable`. This special kind of table can store instances of any Kotlin `sealed class`. The approach used is inspired by the "table per subclass without discriminator" approach [used in Hibernate](https://docs.jboss.org/hibernate/orm/4.3/manual/en-US/html/ch10.html#inheritance-tablepersubclass).

Great care has been taken in order to make sure that the advantages of polymorphism are reflected in Krate's `repository` system: CRUD operations regarding entities of a variant type can be done on either a repository for the base type or for the variant type, and operations regarding entities of the base type can be performed using a repository for the base type.

In other words, given this model:

```kotlin
@SqlTable(People::class)
sealed class Person(val name: String, uuid: UUID) : Entity(uuid) {

    @SqlTable(People.Teachers::class)
    class Teacher(name: String, val teachingSubject: String, uuid: UUID) : Person(name, uuid)
    
    @SqlTable(People.Students::class)
    class Teacher(name: String, val grade: Int, uuid: UUID) : Person(name, uuid)
    
}
```

Either `repository<Person>()` or `repository<Teacher>()` can be used to perform any CRUD operation using a valid teacher UUID; the latter would be used when a specialized type needs to be returned. Internally, this is achieved using appropriate conditional `super` invocations and method overrides.

Please note that polymorphic entity table support is experimental and potentially unstable.

## Features

- `Entity` to `Table` binding
- Extensions for `exposed`'s tables
- Query Optimizer
- Default `Repository` implementation 

## Documentation

The documentation can be found [here](https://docs.31416.dev/krate)

## Installation

This library is hosted on jitpack.

#### Sample  (Gradle Kotlin DSL) :

```kotlin
repositories {
    maven("https://jitpack.io")
}

/* ... */

dependencies {
    implementation("com.github.blogify-dev", "krate", "master-SNAPSHOT")
}

```

## Example

```kotlin
@SqlTable(People::class)
class Person(val name: String, val age: Int, uuid: UUID) : Entity(uuid)

@SqlTable(Boat::class)
class Boat (
    val name: String,
    val length: Int,
    val tonnage: Int,
    val captain: Person,
    val sailors: Set<Person>,
    uuid: UUID
) : Entity(uuid)

object People : EntityTable<Person>(klass = Person::class, name = "people") {
    val name = text    ("name")
    val age  = integer ("age")
   
    init {
        bind(uuid, Person::uuid)
        bind(name, Person::name)
        bind(age, Person::age)
    }
}


object Boats : EntityTable<Boat>(klass = Boat::class, name = "boats") {
    val name    = text      ("name")
    val length  = integer   ("length")
    val tonnage = integer   ("tonnage")
    val captain = parentKey ("captain", People)
   
    init {
        bind(uuid, Boat::uuid)
        bind(name, Boat::name)
        bind(length, Boat::length)
        bind(tonnage, Boat::tonnage)
        bind(captain, Boat::captain)
    }
}
```

# Contributions

Issues and Pull Requests are welcome
