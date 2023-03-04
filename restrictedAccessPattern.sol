//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;

contract RestrictedAccess {

/*
We will create a contract called RestrictedAccess with two variables owner and creationTime, 
and I am going to initialize them. We will set the owner to be the owner of the contract (msg.sender) 
and for the creationTime we are going to use a global variable (block.timestamp).
*/
    address public owner = msg.sender;
    uint public creationTime = block.timestamp;
    
/*
We want to restrict the address of the changeOwner function. We only want the current owner to change the owner's address. 
To do this we can use a modifier - onlyBy. We will name it onlyBy and it will take as input an address. The modifier have a 
requirement that the msg.sender should be equal to the input of the _account. 
If it fails, then we can write a statement "Sender not authorized!" 
*/
    modifier onlyBy(address _account) { 
        require(msg.sender == _account,
        'Sender not authorized!'
        );
        _;
    }

/*
We will create a new modifier (onlyAfter) which will take an uint as input and will name it _time. 
For the require we will write that block.timestamp should be >= the amount of _time, 
otherwise, if it doesn't run, we will receive the message 'Function was called too early!'.
*/
     modifier onlyAfter(uint _time) {
        require(block.timestamp >= _time,
        'Function was called too early!'
        );
        _;
    }

/*
We will create a modifier called costs which takes an _amount parameter and require that msg.value is >= to the amount. 
If the msg.value is not >= to the amount, then return a string that says "Not enough Ether provided!".
*/
    modifier costs(uint _amount) {
        require(msg.value >= _amount,
        'Not enough Ether provided!'
        );
        _;
    }

/*
We will write a payable function called forceOwnerChange which takes an address called _newOwner. 
The function needs 200 Ether to set the owner to the contract to the new owner of the address.
*/
    function forceOwnerChange (address _newOwner) payable public costs(200 ether) {
        owner = _newOwner;
    }

// The function will take an address as an argument and call it _owner, and It should return owner = _owner. 
    function changeOwner(address _owner) onlyBy(owner) public {
        owner = _owner;
    }

// We will write a function to disown the owner after 3 weeks from the contract creation time
    function disown() onlyBy(owner) onlyAfter(creationTime + 3 weeks) public {
        delete owner;
    }
}
