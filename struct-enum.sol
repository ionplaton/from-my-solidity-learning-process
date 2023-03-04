//SPDX-License-Identifier: MIT
pragma solidity ^0.8;


/*
A struct is a collection of key->value pairs similar to a mapping, but the values can have different types. 
Structs provide a way to define new data types. It introduces a new complex data type that is composed of other elementary data types. 
We use structs to represent a singular thing that has properties like a Car, a Person, a Request and so on, 
and we use mappings to represent a collection of things like collection of Cars, Persons, Requests. 
A struct can be declared outside or inside of a contract. A struct is saved in storage and if declared 
inside a function it references storage by default. If declaring a struct outside of a contract allows it to be 
shared by multiple contracts. Let's declare such a struct.
*/
struct Instructor {
    uint age;
    string name;
    address addr;
}

/*
we will create a contract. (Being declared outside of the contract, we can declare a variable 
of type Instructor in multiple contracts.) So Instructor, the type public, the name of the variable academyInstructor 
*/
contract Academy {
    Instructor public academyInstructor;

/*
Enums are one way of creating a user-defined type in Solidity. Enums are especially useful when we need to specify
the current state or flow of a smart contract. Let's write in our contract - enum followed by the new user defined 
type (State) and a pair of curly brackets. 
*/
    enum State {Open, Closed, Unknown}
    
// After this, we will declare a new public state variable of type State which we will initialize it as State.Open
    State public academyState;

// Now we will initialize the struct variable using the constructor (msg.sender is the address that deploys the contract).
    constructor(uint _age, string memory _name) {
        academyInstructor.age = _age;
        academyInstructor.name = _name;
        academyInstructor.addr = msg.sender;
    }

/* We will declare a new function called changeInstructor to see how is modifying a struct variable. 
By default struct references storage. We will create a temporary memory struct (myInstructor) initializing it with the 
given values and copying it to the storage struct, the state variable of the contract. 
So we declare a new memory variable of type struct (Instructor) and name it myInstructor. 
This memory struct variable will be equal the name of the struct (Instructor), a pair of semicolon and inside the 
parentheses a pair of curly brackets, then age: _age, name: _name, addr: _addr.
In the changeInstructor function, we will test the academyState variable before modyfing the Instructor's data. 
So, we will change the Instructor's data, only if the academy is open. We will use an if statement. 
*/
    function changeInstructor(uint _age, string memory _name, address _addr) public {
        if(academyState == State.Open) {
            Instructor memory myInstructor = Instructor({
            age: _age,
            name: _name,
            addr: _addr
            });
            
// Next we will copy the memory struct variable  to the storage one.
            academyInstructor = myInstructor;
        }
    }
}
